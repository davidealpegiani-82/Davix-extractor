package it.davidealpegiani.skyunoextractor

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Parses a user-pasted provider report locally. Credentials are deliberately kept
 * in a dedicated value object and must never be included in AI prompts or logs.
 */
internal data class XtreamReportSource(
    val baseUrl: String,
    val username: String,
    val password: String,
    val liveCategories: List<String>,
    val vodCategories: List<String>,
    val seriesCategories: List<String>,
    val epgUrl: String?
) {
    val playerApiUrl: String get() = baseUrl.trimEnd('/') + "/player_api.php"

    fun redactedSummary(): String = buildString {
        appendLine("Xtream source detected")
        appendLine("Server: $baseUrl")
        appendLine("Username: [REDACTED]")
        appendLine("Password: [REDACTED]")
        appendLine("Live categories: ${liveCategories.size}")
        appendLine("VOD categories: ${vodCategories.size}")
        appendLine("Series categories: ${seriesCategories.size}")
        epgUrl?.let { appendLine("EPG: ${redactUrl(it)}") }
    }
}

/**
 * Extracts only standard Xtream connection details from pasted text.
 * It does not contact the provider and does not execute URLs found in the text.
 */
internal object XtreamReportParser {
    private val labelPatterns = mapOf(
        "username" to listOf("username", "user name", "utente", "user"),
        "password" to listOf("password", "pass", "pwd"),
        "server" to listOf("server url", "server address", "server", "host", "url server", "indirizzo server"),
        "port" to listOf("port", "porta"),
        "epg" to listOf("epg url", "xmltv", "epg")
    )

    fun parse(report: String): XtreamReportSource {
        require(report.isNotBlank()) { "Incolla il report della sorgente." }
        require(report.length <= 2 * 1024 * 1024) { "Il report supera il limite di 2 MiB." }

        val lines = report.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val labeled = LinkedHashMap<String, String>()
        lines.forEach { line ->
            val split = Regex("""^\s*([^:=]{2,40})\s*[:=]\s*(.*?)\s*$""").find(line) ?: return@forEach
            val key = normalizeLabel(split.groupValues[1])
            val value = split.groupValues[2].trim().trim('"', '\'')
            labelPatterns.forEach { (canonical, aliases) ->
                if (aliases.any { normalizeLabel(it) == key } && value.isNotBlank()) {
                    labeled.putIfAbsent(canonical, value)
                }
            }
        }

        // Xtream credentials are also commonly embedded in get.php/player_api.php URLs.
        val candidateUrls = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
            .findAll(report).map { it.value.trimEnd(',', ';', ')', ']') }.toList()
        var urlCredentials: Map<String, String> = emptyMap()
        var urlServer: String? = null
        candidateUrls.forEach { raw ->
            val uri = runCatching { URI(raw) }.getOrNull() ?: return@forEach
            val query = parseQuery(uri.rawQuery.orEmpty())
            if (query.containsKey("username") && query.containsKey("password")) {
                urlCredentials = query
                if (urlServer == null) {
                    val scheme = uri.scheme ?: "http"
                    val port = if (uri.port >= 0) ":${uri.port}" else ""
                    urlServer = "$scheme://${uri.host}$port"
                }
            }
        }

        val username = labeled["username"] ?: urlCredentials["username"]
            ?: throw IllegalArgumentException("Nel testo non trovo il nome utente Xtream.")
        val password = labeled["password"] ?: urlCredentials["password"]
            ?: throw IllegalArgumentException("Nel testo non trovo la password Xtream.")

        val rawServer = labeled["server"] ?: urlServer
            ?: throw IllegalArgumentException("Nel testo non trovo l'indirizzo del server.")
        val baseUrl = normalizeBaseUrl(rawServer, labeled["port"])
        val epg = labeled["epg"]?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: candidateUrls.firstOrNull { it.contains("xmltv", true) || it.contains("epg", true) }

        return XtreamReportSource(
            baseUrl = baseUrl,
            username = username,
            password = password,
            liveCategories = extractCategories(lines, "live"),
            vodCategories = extractCategories(lines, "vod"),
            seriesCategories = extractCategories(lines, "series"),
            epgUrl = epg
        )
    }

    private fun normalizeBaseUrl(raw: String, separatePort: String?): String {
        var value = raw.trim().trimEnd('/', ',', ';')
        if (!value.contains("://")) value = "http://$value"
        val uri = runCatching { URI(value) }.getOrElse {
            throw IllegalArgumentException("Indirizzo del server non valido.")
        }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Il server deve usare HTTP o HTTPS."
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Indirizzo del server non valido."
        }
        val port = separatePort?.toIntOrNull()
        require(separatePort.isNullOrBlank() || (port != null && port in 1..65535)) {
            "Porta del server non valida."
        }
        val effectivePort = if (uri.port >= 0) uri.port else port ?: -1
        val authority = uri.host + if (effectivePort >= 0) ":$effectivePort" else ""
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "${uri.scheme.lowercase(Locale.ROOT)}://$authority$path"
    }

    private fun extractCategories(lines: List<String>, kind: String): List<String> {
        val aliases = when (kind) {
            "live" -> listOf("live", "tv", "canali", "live categories", "categorie live")
            "vod" -> listOf("vod", "movies", "movie", "film", "categorie vod")
            else -> listOf("series", "serie", "tv shows", "categorie serie", "serie tv")
        }
        val result = LinkedHashSet<String>()
        var active = false
        lines.forEach { line ->
            val normalized = normalizeLabel(line.trim(':', '[', ']', '#', '*'))
            if (aliases.any { normalized == normalizeLabel(it) || normalized.startsWith(normalizeLabel(it) + " ") }) {
                active = true
                return@forEach
            }
            val isSectionHeading = Regex("""^[A-Za-z][A-Za-z _-]{1,32}\s*:?$""").matches(line) &&
                listOf("live", "live categories", "categorie live", "tv", "vod", "vod categories",
                    "categorie vod", "movies", "movie", "film", "series", "series categories",
                    "categorie serie", "serie", "serie tv", "epg", "server", "account",
                    "username", "password")
                    .any { normalized == normalizeLabel(it) }
            if (active && isSectionHeading) active = false
            if (active) {
                val category = line.removePrefix("-").removePrefix("•").trim()
                if (category.length in 2..120 && !category.contains("://") &&
                    !category.contains("=") && !category.contains(":") &&
                    !category.matches(Regex("""\d+"""))) result.add(category)
            }
        }
        return result.toList()
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) null else {
                val key = decode(pair.substring(0, index)).lowercase(Locale.ROOT)
                key to decode(pair.substring(index + 1))
            }
        }.toMap()

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun normalizeLabel(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), " ").trim()

    private fun redactUrl(value: String): String = runCatching {
        val uri = URI(value)
        val query = uri.rawQuery.orEmpty().split('&').filterNot {
            it.substringBefore('=').equals("username", true) ||
                it.substringBefore('=').equals("password", true) ||
                it.substringBefore('=').equals("token", true)
        }.joinToString("&")
        URI(uri.scheme, uri.rawAuthority, uri.rawPath, query.ifBlank { null }, null).toString()
    }.getOrDefault("[URL REDACTED]")
}
