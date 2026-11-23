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
    val vodCount: Int,
    val episodeCount: Int,
    val skippedSeries: Int
)

/**
 * Reads an authorized Xtream API catalog and converts playable entries to M3U.
 *
 * Requests are deliberately sequential (one HTTP request at a time). In particular,
 * this class never opens media streams to "ping" them: doing so can consume provider
 * connection slots. Real playability is established only by Media3 during playback.
 */
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
        if (userInfo.optString("auth") != "1") {
            throw IOException("Credenziali rifiutate dal server Xtream.")
        }
        val status = userInfo.optString("status")
        if (status.isNotBlank() && !status.equals("Active", true)) {
            throw IOException("Account Xtream non attivo (stato: $status).")
        }

        val liveCategories = categoryMap("get_live_categories")
        val vodCategories = categoryMap("get_vod_categories")
        val seriesCategories = categoryMap("get_series_categories")

        val output = StringBuilder("#EXTM3U\n")
        var liveCount = 0
        var vodCount = 0
        var episodeCount = 0
        var skippedSeries = 0

        progress("Recupero canali Live…")
        val live = getJsonArray(apiUrl("get_live_streams"))
        for (index in 0 until live.length()) {
            val item = live.optJSONObject(index) ?: continue
            val id = item.optString("stream_id").takeIf { it.isNotBlank() } ?: continue
            val name = item.optString("name").ifBlank { "Canale $id" }
            val category = categoryName(liveCategories, item.optString("category_id"), "Live")
            val ext = safeExtension(item.optString("container_extension"), "ts")
            val url = "$base/live/$userSegment/$passwordSegment/$id.$ext"
            appendEntry(output, name, category, item.optString("stream_icon"), item.optString("epg_channel_id"), url)
            liveCount++
        }

        progress("Recupero film VOD…")
        val vod = getJsonArray(apiUrl("get_vod_streams"))
        for (index in 0 until vod.length()) {
            val item = vod.optJSONObject(index) ?: continue
            val id = item.optString("stream_id").takeIf { it.isNotBlank() } ?: continue
            val name = item.optString("name").ifBlank { "Film $id" }
            val category = categoryName(vodCategories, item.optString("category_id"), "Film")
            val ext = safeExtension(item.optString("container_extension"), "mp4")
            val url = "$base/movie/$userSegment/$passwordSegment/$id.$ext"
            appendEntry(output, name, "VOD > $category", item.optString("stream_icon"), "", url)
            vodCount++
        }

        progress("Recupero elenco serie…")
        val series = getJsonArray(apiUrl("get_series"))
        for (index in 0 until series.length()) {
            val item = series.optJSONObject(index) ?: continue
            val seriesId = item.optString("series_id").takeIf { it.isNotBlank() } ?: continue
            val seriesName = item.optString("name").ifBlank { "Serie $seriesId" }
            val category = categoryName(seriesCategories, item.optString("category_id"), "Serie TV")
            progress("Analisi episodi · ${index + 1}/${series.length()} · $seriesName")
            try {
                val info = getJsonObject(apiUrl("get_series_info", "series_id" to seriesId))
                val episodes = info.optJSONObject("episodes")
                if (episodes == null) {
                    skippedSeries++
                    continue
                }
                val seasonKeys = episodes.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                var foundForSeries = 0
                seasonKeys.forEach { seasonKey ->
                    val seasonEpisodes = episodes.optJSONArray(seasonKey) ?: return@forEach
                    for (episodeIndex in 0 until seasonEpisodes.length()) {
                        val episode = seasonEpisodes.optJSONObject(episodeIndex) ?: continue
                        val episodeId = episode.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val extension = safeExtension(episode.optString("container_extension"), "mp4")
                        val episodeTitle = episode.optString("title").ifBlank { "Episodio ${episodeIndex + 1}" }
                        val seasonNumber = episode.optString("season").ifBlank { seasonKey }
                        val displayName = "$seriesName · S${seasonNumber} · $episodeTitle"
                        val episodeUrl = "$base/series/$userSegment/$passwordSegment/$episodeId.$extension"
                        val group = "Serie TV > $category > $seriesName"
                        appendEntry(output, displayName, group, "", "", episodeUrl)
                        episodeCount++
                        foundForSeries++
                    }
                }
                if (foundForSeries == 0) skippedSeries++
            } catch (_: Exception) {
                // A single unavailable series must not abort the remaining catalog.
                skippedSeries++
            }
        }

        if (liveCount + vodCount + episodeCount == 0) {
            throw IOException("Il server non ha restituito flussi Live, film o episodi utilizzabili.")
        }
        return XtreamCatalogResult(output.toString(), liveCount, vodCount, episodeCount, skippedSeries)
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

    private fun categoryName(categories: Map<String, String>, id: String, fallback: String): String =
        categories[id]?.takeIf { it.isNotBlank() } ?: fallback

    private fun appendEntry(
        out: StringBuilder,
        name: String,
        group: String,
        logo: String,
        tvgId: String,
        url: String
    ) {
        out.append("#EXTINF:-1")
        if (tvgId.isNotBlank()) out.append(" tvg-id=\"").append(attribute(tvgId)).append('"')
        out.append(" tvg-name=\"").append(attribute(name)).append('"')
        if (logo.startsWith("http://", true) || logo.startsWith("https://", true)) {
            out.append(" tvg-logo=\"").append(attribute(logo)).append('"')
        }
        out.append(" group-title=\"").append(attribute(group)).append("\",")
            .append(name.replace('\n', ' ').replace('\r', ' '))
            .append('\n').append(url).append('\n')
    }

    private fun apiUrl(action: String? = null, vararg extra: Pair<String, String>): String {
        val params = mutableListOf(
            "username=${queryValue(source.username)}",
            "password=${queryValue(source.password)}"
        )
        if (!action.isNullOrBlank()) params += "action=${queryValue(action)}"
        extra.forEach { (key, value) -> params += "${queryValue(key)}=${queryValue(value)}" }
        return "$base/player_api.php?${params.joinToString("&")}"
    }

    private fun getJsonArray(address: String): JSONArray {
        val body = request(address)
        return try {
            JSONArray(body)
        } catch (error: Exception) {
            throw IOException("Risposta catalogo Xtream non valida.", error)
        }
    }

    private fun getJsonObject(address: String): JSONObject {
        val body = request(address)
        return try {
            JSONObject(body)
        } catch (error: Exception) {
            throw IOException("Risposta account Xtream non valida.", error)
        }
    }

    private fun request(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false // Never forward account credentials to another host.
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "DAVIX-Extractor/1.0")
        }
        try {
            val code = connection.responseCode
            if (code in 300..399) throw IOException("Il server Xtream ha richiesto un reindirizzamento non consentito.")
            if (code !in 200..299) {
                val message = when (code) {
                    401, 403 -> "Accesso Xtream negato (HTTP $code)."
                    429 -> "Troppe richieste al server Xtream (HTTP 429). Riprova più tardi."
                    else -> "Errore del server Xtream (HTTP $code)."
                }
                throw IOException(message)
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

    private fun safeExtension(raw: String, fallback: String): String {
        val value = raw.lowercase(Locale.ROOT).trim().removePrefix(".")
        return value.takeIf { it.matches(Regex("[a-z0-9]{2,8}")) } ?: fallback
    }

    private fun attribute(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("\n", " ").replace("\r", " ")

    private fun queryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun pathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
