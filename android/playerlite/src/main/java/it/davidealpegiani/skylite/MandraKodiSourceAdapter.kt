package it.davidealpegiani.skylite

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class MandraKodiSourceConfig(
    val catalogUrl: String,
    val displayName: String = "MandraKodi",
    val connectTimeoutMs: Int = 12_000,
    val readTimeoutMs: Int = 20_000,
    val maxResponseBytes: Int = 12 * 1024 * 1024
)

data class MandraKodiChannel(
    val stableId: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String = "",
    val category: String = "",
    val tvgId: String = "",
    val tvgName: String = "",
    val sourceName: String = "MandraKodi"
)

/**
 * Imports direct HTTP(S) channel metadata from a configured MandraKodi M3U/JSON
 * export. Call load() from a worker thread. Kodi scripts, resolver actions,
 * protected-page extraction and DRM/authentication workarounds are not executed.
 */
class MandraKodiSourceAdapter {
    fun load(config: MandraKodiSourceConfig): List<MandraKodiChannel> {
        require(isHttpUrl(config.catalogUrl)) { "URL catalogo MandraKodi non valido" }
        val connection = URL(config.catalogUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = config.connectTimeoutMs
            connection.readTimeout = config.readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json, application/x-mpegURL, audio/x-mpegurl, text/plain, */*")
            connection.setRequestProperty("User-Agent", "DAVIX-Player/1.0")
            val status = connection.responseCode
            require(status in 200..299) { "Catalogo MandraKodi non raggiungibile (HTTP " + status + ")" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= config.maxResponseBytes) { "Catalogo troppo grande" }
                    output.write(buffer, 0, count)
                }
            }
            val document = output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF").trim()
            return parse(document, config.displayName)
        } finally {
            connection.disconnect()
        }
    }

    fun parse(document: String, sourceName: String = "MandraKodi"): List<MandraKodiChannel> {
        val content = document.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val channels = if (content.startsWith("#EXTM3U", true)) parseM3u(content, sourceName)
        else parseJson(content, sourceName)
        return channels.distinctBy { it.stableId }
    }

    private fun parseM3u(document: String, sourceName: String): List<MandraKodiChannel> {
        val result = mutableListOf<MandraKodiChannel>()
        var metadata: Metadata? = null
        document.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:", true) -> metadata = parseExtInf(line)
                line.isBlank() || line.startsWith("#") -> Unit
                isHttpUrl(line) -> {
                    val item = metadata ?: Metadata(line.substringAfterLast('/').ifBlank { "Canale" })
                    result += makeChannel(item.name, line, item.logo, item.category, item.tvgId, item.tvgName, sourceName)
                    metadata = null
                }
                else -> metadata = null
            }
        }
        return result
    }

    private fun parseExtInf(line: String): Metadata {
        fun attr(name: String): String {
            val pattern = "(?:^|\\s)" + Regex.escape(name) + "=\"([^\"]*)\""
            return Regex(pattern, RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1).orEmpty()
        }
        val title = line.substringAfterLast(',', "").trim().ifBlank { attr("tvg-name") }
        return Metadata(
            name = title.ifBlank { "Canale" },
            logo = attr("tvg-logo"),
            category = attr("group-title"),
            tvgId = attr("tvg-id"),
            tvgName = attr("tvg-name")
        )
    }

    private fun parseJson(document: String, sourceName: String): List<MandraKodiChannel> {
        val root = runCatching { JSONObject(document) }.getOrNull()
            ?: throw IllegalArgumentException("Formato catalogo non riconosciuto: atteso M3U o JSON")
        val result = mutableListOf<MandraKodiChannel>()

        fun readArray(array: JSONArray, inheritedCategory: String = "") {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").ifBlank {
                    item.optString("title").ifBlank { item.optString("tvg-name") }
                }.trim()
                val url = listOf("url", "stream", "stream_url").asSequence()
                    .map { item.optString(it).trim() }
                    .firstOrNull { isHttpUrl(it) }
                    ?: item.optString("link").trim().takeIf { isDirectMediaUrl(it) }
                    ?: continue
                if (name.isBlank()) continue
                result += makeChannel(
                    name,
                    url,
                    item.optString("logo").ifBlank { item.optString("thumbnail").ifBlank { item.optString("tvg-logo") } },
                    item.optString("category").ifBlank { item.optString("group-title").ifBlank { inheritedCategory } },
                    item.optString("tvgId").ifBlank { item.optString("tvg-id") },
                    item.optString("tvgName").ifBlank { item.optString("tvg-name") },
                    sourceName
                )
            }
        }

        root.optJSONArray("channels")?.let { readArray(it) }
        root.optJSONArray("stations")?.let { readArray(it) }
        root.optJSONArray("groups")?.let { groups ->
            for (index in 0 until groups.length()) {
                val group = groups.optJSONObject(index) ?: continue
                val category = group.optString("name").ifBlank { group.optString("title") }
                group.optJSONArray("stations")?.let { readArray(it, category) }
                group.optJSONArray("channels")?.let { readArray(it, category) }
            }
        }

        // Kodi menu items can represent folders, scripts or resolver actions.
        // Import only explicit direct media URLs; never execute menu actions.
        root.optJSONArray("items")?.let { items ->
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                if (!item.optBoolean("enabled", true)) continue
                val name = item.optString("title").trim()
                val url = item.optString("stream").trim().takeIf { isHttpUrl(it) }
                    ?: item.optString("url").trim().takeIf { isHttpUrl(it) }
                    ?: item.optString("link").trim().takeIf { isDirectMediaUrl(it) }
                    ?: continue
                if (name.isNotBlank()) {
                    result += makeChannel(name, url, item.optString("thumbnail"), item.optString("genre"), "", "", sourceName)
                }
            }
        }
        return result
    }

    private fun makeChannel(
        name: String, url: String, logo: String, category: String,
        tvgId: String, tvgName: String, sourceName: String
    ): MandraKodiChannel {
        val cleanName = name.trim().replace(Regex("\\s+"), " ")
        val identity = sourceName + "|" + cleanName.lowercase(Locale.ROOT) + "|" + url.trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val id = digest.take(12).joinToString("") { "%02x".format(it) }
        return MandraKodiChannel(
            stableId = "mandrakodi:" + id,
            name = cleanName,
            streamUrl = url.trim(),
            logoUrl = logo.trim().takeIf { isHttpUrl(it) }.orEmpty(),
            category = category.trim(),
            tvgId = tvgId.trim(),
            tvgName = tvgName.trim(),
            sourceName = sourceName
        )
    }

    private fun isDirectMediaUrl(value: String): Boolean {
        if (!isHttpUrl(value)) return false
        val path = runCatching { URL(value).path.lowercase(Locale.ROOT) }.getOrDefault("")
        return listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".ts", ".mp3", ".aac", ".m4a", ".ism", ".isml")
            .any { path.endsWith(it) }
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", true) || value.startsWith("http://", true)

    private data class Metadata(
        val name: String,
        val logo: String = "",
        val category: String = "",
        val tvgId: String = "",
        val tvgName: String = ""
    )
}
