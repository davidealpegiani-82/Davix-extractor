package it.davidealpegiani.skyunoextractor

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class XtreamCatalogResult(
    val m3u: String,
    val liveCount: Int,
    val excludedNonItalianCount: Int
)

/** Imports only Italian live TV streams. VOD and series API actions are never called. */
internal class XtreamCatalogClient(
    private val source: XtreamReportSource,
    private val progress: (String) -> Unit = {}
) {
    private val base = source.baseUrl.trimEnd('/')
    private val userSegment = pathSegment(source.username)
    private val passwordSegment = pathSegment(source.password)

    fun downloadCatalog(): XtreamCatalogResult {
        progress("Verifica account Xtream…")
        val account = getJsonObject(apiUrl())
        val userInfo = account.optJSONObject("user_info")
            ?: throw IOException("Risposta Xtream non valida: dati account assenti.")
        if (userInfo.optString("auth") != "1") throw IOException("Credenziali rifiutate dal server Xtream.")
        val status = userInfo.optString("status")
        if (status.isNotBlank() && !status.equals("Active", true)) {
            throw IOException("Account Xtream non attivo (stato: $status).")
        }

        progress("Recupero categorie Live…")
        val liveCategories = categoryMap("get_live_categories")
        progress("Recupero esclusivo dei canali Live italiani…")
        val live = getJsonArray(apiUrl("get_live_streams"))
        val output = StringBuilder("#EXTM3U\n")
        var liveCount = 0
        var excludedNonItalianCount = 0

        for (index in 0 until live.length()) {
            val item = live.optJSONObject(index) ?: continue
            val id = item.optString("stream_id").takeIf { it.isNotBlank() } ?: continue
            val name = item.optString("name").ifBlank { "Canale $id" }
            val category = liveCategories[item.optString("category_id")].orEmpty()
            val metadata = listOf(
                item.optString("language"), item.optString("lang"), item.optString("country"),
                item.optString("country_code"), item.optString("countryCode"),
                item.optString("tvg-country"), item.optString("tvg-language"),
                item.optString("stream_language"), item.optString("epg_channel_id")
            ).filter { it.isNotBlank() }

            if (!ItalianLiveChannelFilter.isItalian(name, category, metadata)) {
                excludedNonItalianCount++
                continue
            }

            val ext = safeExtension(item.optString("container_extension"), "ts")
            val url = "$base/live/$userSegment/$passwordSegment/$id.$ext"
            appendEntry(output, name, category.ifBlank { "Italia" }, item.optString("stream_icon"),
                item.optString("epg_channel_id"), url)
            liveCount++
        }

        if (liveCount == 0) {
            throw IOException("Nessun canale Live italiano riconosciuto. Film, VOD e serie non sono stati importati. Controlla i nomi e le categorie forniti dal server.")
        }
        progress("Importazione pronta · $liveCount canali Live italiani.")
        return XtreamCatalogResult(output.toString(), liveCount, excludedNonItalianCount)
    }

    private fun categoryMap(action: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val categories = getJsonArray(apiUrl(action))
        for (index in 0 until categories.length()) {
            val item = categories.optJSONObject(index) ?: continue
            val id = item.optString("category_id")
            val name = item.optString("category_name")
            if (id.isNotBlank() && name.isNotBlank()) result[id] = name
        }
        return result
    }

    private fun appendEntry(out: StringBuilder, name: String, group: String, logo: String, tvgId: String, url: String) {
        out.append("#EXTINF:-1")
        if (tvgId.isNotBlank()) out.append(" tvg-id=\"").append(attribute(tvgId)).append('"')
        out.append(" tvg-name=\"").append(attribute(name)).append('"')
        if (logo.startsWith("http://", true) || logo.startsWith("https://", true)) {
            out.append(" tvg-logo=\"").append(attribute(logo)).append('"')
        }
        out.append(" group-title=\"").append(attribute(group)).append("\",")
            .append(name.replace('\n', ' ').replace('\r', ' ')).append('\n').append(url).append('\n')
    }

    private fun apiUrl(action: String? = null, vararg extra: Pair<String, String>): String {
        val params = mutableListOf("username=${queryValue(source.username)}", "password=${queryValue(source.password)}")
        if (!action.isNullOrBlank()) params += "action=${queryValue(action)}"
        extra.forEach { (key, value) -> params += "${queryValue(key)}=${queryValue(value)}" }
        return "$base/player_api.php?${params.joinToString("&")}"
    }

    private fun getJsonArray(address: String): JSONArray = try {
        JSONArray(request(address))
    } catch (error: Exception) {
        if (error is IOException) throw error
        throw IOException("Risposta catalogo Xtream non valida.", error)
    }

    private fun getJsonObject(address: String): JSONObject = try {
        JSONObject(request(address))
    } catch (error: Exception) {
        if (error is IOException) throw error
        throw IOException("Risposta account Xtream non valida.", error)
    }

    private fun request(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "DAVIX-Extractor/1.0")
        }
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?.let(::safeLocation)
                val redirectDetails = buildList {
                    if (!location.isNullOrBlank()) add("Destinazione: $location")
                    add("Endpoint: ${safeEndpoint(address)}")
                }.joinToString(" · ")
                throw IOException("Il server Xtream ha richiesto un reindirizzamento HTTP $code non seguito automaticamente. $redirectDetails")
            }
            if (code !in 200..299) {
                val serverMessage = runCatching { connection.responseMessage }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                val contentType = connection.getHeaderField("Content-Type")?.take(120)
                val responseBody = readErrorBody(connection)
                    .let { body ->
                        source.username.takeIf { it.isNotEmpty() }?.let { body.replace(it, "[UTENTE]") } ?: body
                    }
                    .let { body ->
                        source.password.takeIf { it.isNotEmpty() }?.let { body.replace(it, "[PASSWORD]") } ?: body
                    }
                    .take(600)
                    .replace(Regex("\\s+"), " ")
                val diagnosticHeaders = listOf(
                    "Server" to connection.getHeaderField("Server"),
                    "Via" to connection.getHeaderField("Via"),
                    "CF-Ray" to connection.getHeaderField("CF-Ray"),
                    "X-Cache" to connection.getHeaderField("X-Cache"),
                    "X-Request-ID" to connection.getHeaderField("X-Request-ID")
                ).mapNotNull { (name, value) ->
                    value?.takeIf { it.isNotBlank() }?.let { "$name: ${it.take(160)}" }
                }
                val details = buildList {
                    if (!serverMessage.isNullOrBlank()) add(serverMessage)
                    add("Endpoint: ${safeEndpoint(address)}")
                    if (!contentType.isNullOrBlank()) add("Content-Type: $contentType")
                    addAll(diagnosticHeaders)
                    if (responseBody.isNotBlank()) add("Risposta: $responseBody")
                    else add("Corpo risposta: assente o non leggibile")
                }.joinToString(" · ")
                val message = when (code) {
                    401, 403 -> "Accesso Xtream negato (HTTP $code)."
                    429 -> "Troppe richieste al server Xtream (HTTP 429). Riprova più tardi."
                    513 -> "Il server o un intermediario ha rifiutato la richiesta iniziale Xtream (HTTP 513)."
                    else -> "Errore del server Xtream (HTTP $code)."
                }
                throw IOException("$message $details")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > 32 * 1024 * 1024) throw IOException("Risposta Xtream oltre il limite di 32 MiB.")
                    output.write(buffer, 0, read)
                }
            }
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /** Returns only scheme, host, port and path; query parameters contain credentials. */
    private fun safeEndpoint(address: String): String = runCatching {
        val url = URL(address)
        buildString {
            append(url.protocol).append("://").append(url.host)
            if (url.port != -1) append(":").append(url.port)
            append(url.path)
        }
    }.getOrDefault("[endpoint non disponibile]")

    /** Removes query and fragment so redirect URLs cannot expose account credentials. */
    private fun safeLocation(location: String): String = runCatching {
        val url = URL(location)
        buildString {
            append(url.protocol).append("://").append(url.host)
            if (url.port != -1) append(":").append(url.port)
            append(url.path)
        }
    }.getOrDefault("[destinazione non leggibile]")

    private fun readErrorBody(connection: HttpURLConnection): String {
        val stream = runCatching { connection.errorStream }.getOrNull() ?: return ""
        return runCatching {
            stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                var total = 0
                while (total < 4096) {
                    val read = input.read(buffer, 0, minOf(buffer.size, 4096 - total))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                String(output.toByteArray(), StandardCharsets.UTF_8)
            }
        }.getOrDefault("")
    }

    private fun safeExtension(raw: String, fallback: String): String {
        val value = raw.lowercase(Locale.ROOT).trim().removePrefix(".")
        return value.takeIf { it.matches(Regex("[a-z0-9]{2,8}")) } ?: fallback
    }

    private fun attribute(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("\n", " ").replace("\r", " ")

    private fun queryValue(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun pathSegment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
