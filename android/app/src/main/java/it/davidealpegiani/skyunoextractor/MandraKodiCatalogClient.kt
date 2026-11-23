package it.davidealpegiani.skyunoextractor

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal data class MandraKodiCatalogEntry(
    val title: String,
    val folderUrl: String? = null,
    val inlineDocument: String? = null,
    val streamUrl: String? = null,
    val logoUrl: String = "",
    val category: String = ""
) {
    val isFolder: Boolean get() = folderUrl != null || inlineDocument != null
}

internal data class MandraKodiCatalogPage(
    val title: String,
    val entries: List<MandraKodiCatalogEntry>,
    val sourceUrl: String = ""
)

/**
 * Read-only browser for the public MandraKodi menu protocol.
 *
 * It follows only explicit HTTP(S) external-menu/M3U links and imports direct
 * HTTP(S) links. Kodi resolver actions, scripts, plugin:// URLs, regex pages,
 * local Ace streams and protected playback integrations are intentionally ignored.
 */
internal class MandraKodiCatalogClient(
    private val userAgent: String,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 25_000,
    private val maxResponseBytes: Int = 12 * 1024 * 1024
) {
    companion object {
        const val ROOT_URL = "https://raw.githubusercontent.com/mandrakodi/mandrakodi.github.io/main/data/disclaimer.json"
        private const val MAX_ENTRIES = 1500
    }

    fun loadRoot(): MandraKodiCatalogPage = load(ROOT_URL)

    fun load(url: String): MandraKodiCatalogPage {
        var currentUrl = resolveSafeMenuUrl(url, ROOT_URL)
            ?: throw IllegalArgumentException("I menu MandraKodi devono essere raggiunti tramite HTTPS.")
        var redirects = 0
        while (true) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json, application/x-mpegURL, audio/x-mpegurl, text/plain, */*")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", userAgent)
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirects++ >= 5) throw IOException("Troppi reindirizzamenti del catalogo MandraKodi.")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Reindirizzamento MandraKodi senza destinazione.")
                    currentUrl = resolveSafeMenuUrl(location, currentUrl)
                        ?: throw IOException("Reindirizzamento MandraKodi non HTTPS o verso un indirizzo locale: bloccato.")
                    continue
                }
                if (code !in 200..299) {
                    throw IOException("MandraKodi non ha risposto correttamente (HTTP $code). Controlla credenziali e versione add-on.")
                }
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxResponseBytes) throw IOException("La risposta MandraKodi supera il limite di 12 MiB.")
                        output.write(buffer, 0, count)
                    }
                }
                return parse(output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF"), currentUrl)
            } finally {
                connection.disconnect()
            }
        }
    }

    fun open(entry: MandraKodiCatalogEntry, baseUrl: String): MandraKodiCatalogPage {
        entry.inlineDocument?.let { return parse(it, baseUrl) }
        val target = entry.folderUrl ?: throw IllegalArgumentException("Voce MandraKodi non navigabile.")
        return load(resolveSafeMenuUrl(target, baseUrl)
            ?: throw IllegalArgumentException("Il collegamento al menu MandraKodi deve usare HTTPS."))
    }

    fun parse(document: String, sourceUrl: String = ROOT_URL): MandraKodiCatalogPage {
        val body = document.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (body.startsWith("#EXTM3U", true)) return parseM3u(body, sourceUrl)
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IllegalArgumentException("Formato MandraKodi non riconosciuto: atteso menu JSON o playlist M3U.")

        // Special case used by the add-on to delegate to another menu endpoint.
        if (root.optString("name") == "plugin.video.mandrakodi") {
            val groups = root.optJSONArray("groups")
            val stationUrl = groups?.optJSONObject(0)?.optJSONArray("stations")
                ?.optJSONObject(0)?.optString("url").orEmpty()
            resolveSafeMenuUrl(stationUrl, sourceUrl)?.let {
                return MandraKodiCatalogPage(root.optString("title").ifBlank { "MandraKodi" },
                    listOf(MandraKodiCatalogEntry("Apri catalogo", folderUrl = it)), sourceUrl)
            }
        }

        val entries = mutableListOf<MandraKodiCatalogEntry>()
        root.optJSONArray("items")?.let { items ->
            for (index in 0 until minOf(items.length(), MAX_ENTRIES)) {
                items.optJSONObject(index)?.let { item -> parseMenuItem(item, sourceUrl)?.let(entries::add) }
            }
        }

        root.optJSONArray("channels")?.let { channels ->
            for (index in 0 until minOf(channels.length(), MAX_ENTRIES)) {
                channels.optJSONObject(index)?.let { channel ->
                    parseChannelFolder(channel, sourceUrl)?.let(entries::add)
                }
            }
        }

        root.optJSONArray("groups")?.let { groups ->
            for (index in 0 until minOf(groups.length(), MAX_ENTRIES)) {
                val group = groups.optJSONObject(index) ?: continue
                val name = group.optString("name").ifBlank { group.optString("title") }.ifBlank { "Sezione ${index + 1}" }
                val nested = JSONObject()
                group.optJSONArray("items")?.let { nested.put("items", it) }
                group.optJSONArray("channels")?.let { nested.put("channels", it) }
                group.optJSONArray("stations")?.let { nested.put("channels", it) }
                if (nested.length() > 0) {
                    entries.add(MandraKodiCatalogEntry(name, inlineDocument = nested.toString()))
                }
            }
        }

        val title = root.optString("title").ifBlank { root.optString("name") }.ifBlank { "Catalogo MandraKodi" }
        if (entries.isEmpty()) {
            throw IllegalArgumentException("Questa sezione non contiene voci importabili o navigabili da DAVIX.")
        }
        return MandraKodiCatalogPage(title, entries.distinctBy {
            it.title.lowercase(Locale.ROOT) + "|" + (it.folderUrl ?: it.streamUrl ?: it.inlineDocument.orEmpty())
        }, sourceUrl)
    }

    private fun parseChannelFolder(channel: JSONObject, baseUrl: String): MandraKodiCatalogEntry? {
        if (!isEnabled(channel)) return null
        if (listOf("myresolve", "regexPage", "personal", "chrome", "yatse", "magnet", "f4m", "pvr", "apk").any(channel::has)) return null
        val name = channel.optString("name").ifBlank { channel.optString("title") }.trim()
        if (name.isBlank()) return null
        val nested = JSONObject()
        channel.optJSONArray("items")?.let { nested.put("items", it) }
        channel.optJSONArray("channels")?.let { nested.put("channels", it) }
        channel.optJSONArray("groups")?.let { nested.put("groups", it) }
        if (nested.length() > 0) return MandraKodiCatalogEntry(name, inlineDocument = nested.toString())
        val external = channel.optString("externallink").ifBlank { channel.optString("externallink2") }
        resolveSafeMenuUrl(external, baseUrl)?.let { return MandraKodiCatalogEntry(name, folderUrl = it) }
        val url = channel.optString("url").ifBlank { channel.optString("stream") }
        if (isDirectHttpUrl(url, baseUrl)) {
            return if (isLikelyMenuUrl(url)) resolveSafeMenuUrl(url, baseUrl)?.let { MandraKodiCatalogEntry(name, folderUrl = it) }
            else MandraKodiCatalogEntry(name, streamUrl = resolveSafeUrl(url, baseUrl), category = channel.optString("category"))
        }
        return null
    }

    private fun parseMenuItem(item: JSONObject, baseUrl: String): MandraKodiCatalogEntry? {
        if (!isEnabled(item)) return null
        if (listOf("myresolve", "regexPage", "personal", "chrome", "yatse", "magnet", "f4m", "pvr", "apk").any(item::has)) return null
        val title = item.optString("title").replace(Regex("\\[[^]]*]"), "").replace(Regex("\\s+"), " ").trim()
            .ifBlank { item.optString("name").trim() }
        if (title.isBlank()) return null

        val external = item.optString("externallink").ifBlank { item.optString("externallink2") }
        resolveSafeMenuUrl(external, baseUrl)?.let { return MandraKodiCatalogEntry(title, folderUrl = it) }

        val m3u = item.optString("m3u")
        resolveSafeMenuUrl(m3u, baseUrl)?.let { return MandraKodiCatalogEntry(title, folderUrl = it) }

        val nested = JSONObject()
        item.optJSONArray("items")?.let { nested.put("items", it) }
        item.optJSONArray("channels")?.let { nested.put("channels", it) }
        item.optJSONArray("groups")?.let { nested.put("groups", it) }
        if (nested.length() > 0) return MandraKodiCatalogEntry(title, inlineDocument = nested.toString())

        // Explicit ffmpeg_link and ordinary link are direct-play actions in Kodi.
        // Only HTTP(S) links are imported; resolver/plugin actions are not interpreted.
        val direct = item.optString("ffmpeg_link").ifBlank { item.optString("link") }
        val stream = resolveSafeUrl(direct, baseUrl) ?: return null
        if (direct.contains("|") || direct.startsWith("plugin://", true) ||
            direct.equals("ignore", true) || direct.equals("ignoreme", true)) return null
        return MandraKodiCatalogEntry(
            title = title,
            streamUrl = stream,
            logoUrl = resolveSafeUrl(item.optString("thumbnail"), baseUrl).orEmpty(),
            category = item.optString("genre").ifBlank { item.optString("category") }
        )
    }

    private fun parseM3u(document: String, baseUrl: String): MandraKodiCatalogPage {
        val entries = mutableListOf<MandraKodiCatalogEntry>()
        var title = "Canale"
        var logo = ""
        var category = ""
        document.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:", true) -> {
                    title = line.substringAfterLast(',', "").trim().ifBlank { "Canale" }
                    logo = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1).orEmpty()
                    category = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1).orEmpty()
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    val stream = resolveSafeUrl(line, baseUrl)
                    if (stream != null) entries.add(MandraKodiCatalogEntry(title, streamUrl = stream,
                        logoUrl = resolveSafeUrl(logo, baseUrl).orEmpty(), category = category))
                    title = "Canale"
                    logo = ""
                    category = ""
                }
            }
        }
        if (entries.isEmpty()) throw IllegalArgumentException("La playlist M3U MandraKodi non contiene flussi HTTP(S) importabili.")
        return MandraKodiCatalogPage("Playlist MandraKodi", entries.take(MAX_ENTRIES), baseUrl)
    }

    private fun isEnabled(item: JSONObject): Boolean {
        val value = item.opt("enabled") ?: return true
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> !value.equals("false", true) && value != "0"
            else -> true
        }
    }

    private fun isDirectHttpUrl(value: String, baseUrl: String): Boolean =
        resolveSafeUrl(value, baseUrl) != null

    private fun isLikelyMenuUrl(value: String): Boolean {
        val path = runCatching { URL(value).path.lowercase(Locale.ROOT) }.getOrDefault("")
        return path.endsWith(".json") || path.endsWith(".m3u")
    }

    private fun resolveSafeMenuUrl(value: String, baseUrl: String): String? =
        resolveSafeUrl(value, baseUrl)?.takeIf { runCatching { URL(it).protocol.equals("https", true) }.getOrDefault(false) }

    private fun resolveSafeUrl(value: String, baseUrl: String): String? {
        val raw = value.trim()
        if (raw.isBlank() || raw.startsWith("plugin://", true) || raw.startsWith("file:", true)) return null
        return runCatching {
            val url = URL(URL(baseUrl), raw)
            val protocol = url.protocol.lowercase(Locale.ROOT)
            val host = url.host.lowercase(Locale.ROOT)
            if (protocol != "http" && protocol != "https") return@runCatching null
            if (host.isBlank() || host == "localhost" || host.endsWith(".local") ||
                host == "127.0.0.1" || host == "0.0.0.0" ||
                host.startsWith("10.") || host.startsWith("192.168.") ||
                host.matches(Regex("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))) return@runCatching null
            url.toString()
        }.getOrNull()
    }
}
