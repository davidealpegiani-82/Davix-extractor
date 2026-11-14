package it.davidealpegiani.skylite

import android.content.Context
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

internal data class EpgChannelInput(val name: String, val url: String, val tvgId: String = "", val tvgName: String = "")
internal data class EpgGuide(val nowTitle: String = "", val nextTitle: String = "", val nowStartMs: Long = 0L, val nowStopMs: Long = 0L, val epgId: String = "")
internal data class EpgRefreshResult(val guides: Map<String, EpgGuide>, val matchedCount: Int, val sourceLabel: String)

internal object EpgGuideManager {
    private const val PREFS = "davix_epg_cache"
    private const val LAST_SYNC = "last_sync"
    private const val GUIDE_CACHE = "guide_cache"
    private const val MATCH_CACHE = "match_cache"
    private const val REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private val sources = listOf(
        "IPTV-EPG Italia" to "https://iptv-epg.org/files/epg-it.xml.gz",
        "Sky Italia" to "https://iptv-org.github.io/epg/guides/it/guidatv.sky.it.xml"
    )
    private data class Program(val start: Long, val stop: Long, val title: String)
    private data class Dataset(val channelNames: Map<String, List<String>>, val programs: Map<String, List<Program>>)

    fun shouldRefresh(context: Context): Boolean =
        System.currentTimeMillis() - context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_SYNC, 0L) >= REFRESH_INTERVAL_MS

    fun formatTime(value: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(value))

    fun readCached(context: Context): Map<String, EpgGuide> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(GUIDE_CACHE, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val url = keys.next()
                    val item = json.optJSONObject(url) ?: continue
                    put(url, EpgGuide(item.optString("now"), item.optString("next"), item.optLong("start"), item.optLong("stop"), item.optString("id")))
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun refresh(context: Context, channels: List<EpgChannelInput>): EpgRefreshResult {
        require(channels.isNotEmpty()) { "Nessun canale da associare." }
        val datasets = mutableListOf<Pair<String, Dataset>>()
        for ((label, address) in sources) {
            try {
                val data = download(address)
                if (data.channelNames.isNotEmpty()) datasets.add(label to data)
            } catch (_: Exception) {
                // Una fonte indisponibile non blocca il tentativo con la successiva.
            }
        }
        if (datasets.isEmpty()) throw IllegalStateException("Nessuna fonte XMLTV raggiungibile.")

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldMatches = runCatching { JSONObject(prefs.getString(MATCH_CACHE, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val newMatches = JSONObject()
        val now = System.currentTimeMillis()
        val result = linkedMapOf<String, EpgGuide>()
        var matched = 0
        val usedSources = linkedSetOf<String>()

        for (channel in channels) {
            val baseKey = normalize(channel.tvgId) + "|" + normalize(channel.tvgName.ifBlank { channel.name })
            var selected: Pair<String, String>? = null
            for ((label, dataset) in datasets) {
                val cachedId = oldMatches.optString(baseKey + "|" + label).takeIf { it.isNotBlank() }
                val id = findMatch(channel, dataset, cachedId)
                if (id != null) {
                    selected = label to id
                    // Se la fonte contiene solo l'anagrafica ma nessun programma utile,
                    // continua con la successiva prima di scegliere questa associazione.
                    val hasProgram = dataset.programs[id].orEmpty().any { it.stop > now }
                    if (hasProgram) break
                }
            }
            if (selected == null) continue
            val (source, id) = selected
            newMatches.put(baseKey + "|" + source, id)
            usedSources.add(source)
            val programmes = datasets.firstOrNull { it.first == source }?.second?.programs?.get(id).orEmpty().sortedBy { it.start }
            val current = programmes.firstOrNull { it.start <= now && it.stop > now }
            val next = programmes.firstOrNull { it.start > now }
            result[channel.url] = EpgGuide(current?.title.orEmpty(), next?.title.orEmpty(), current?.start ?: 0L, current?.stop ?: 0L, id)
            matched++
        }

        val cache = JSONObject()
        result.forEach { (url, guide) ->
            cache.put(url, JSONObject().put("now", guide.nowTitle).put("next", guide.nextTitle).put("start", guide.nowStartMs).put("stop", guide.nowStopMs).put("id", guide.epgId))
        }
        prefs.edit().putString(GUIDE_CACHE, cache.toString()).putString(MATCH_CACHE, newMatches.toString()).putLong(LAST_SYNC, now).apply()
        return EpgRefreshResult(result, matched, usedSources.joinToString(" + ").ifBlank { "fonti XMLTV" })
    }

    private fun findMatch(channel: EpgChannelInput, dataset: Dataset, cachedId: String?): String? {
        val ids = dataset.channelNames.keys
        if (!cachedId.isNullOrBlank()) ids.firstOrNull { it.equals(cachedId, true) }?.let { return it }
        if (channel.tvgId.isNotBlank()) ids.firstOrNull { it.equals(channel.tvgId, true) }?.let { return it }
        val targets = listOf(channel.tvgName, channel.name).map(::normalize).filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return null
        val exact = dataset.channelNames.filterValues { names -> names.any { normalize(it) in targets } }.keys.toList()
        if (exact.size == 1) return exact.first()
        if (exact.size > 1) return null
        val scored = dataset.channelNames.map { (id, names) ->
            id to (names.maxOfOrNull { candidate -> targets.maxOfOrNull { target -> similarity(target, normalize(candidate)) } ?: 0.0 } ?: 0.0)
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val runnerUp = scored.getOrNull(1)?.second ?: 0.0
        return best.first.takeIf { best.second >= 0.92 && best.second - runnerUp >= 0.10 }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("\\b(hd|fhd|uhd|4k|hevc|1080p|720p|sd)\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}+]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }

    private fun download(address: String): Dataset {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/xml,text/xml,*/*")
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", "DAVIX-Player/1.2 EPG")
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP " + connection.responseCode)
            val buffered = BufferedInputStream(connection.inputStream)
            buffered.mark(2)
            val first = buffered.read()
            val second = buffered.read()
            buffered.reset()
            val stream: InputStream = if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
            stream.use { return parse(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(input: InputStream): Dataset {
        val parser = Xml.newPullParser()
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        parser.setInput(input, "UTF-8")
        val names = linkedMapOf<String, List<String>>()
        val programmes = linkedMapOf<String, MutableList<Program>>()
        val now = System.currentTimeMillis()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (parser.name) {
                "channel" -> {
                    val id = parser.getAttributeValue(null, "id").orEmpty()
                    val displayNames = mutableListOf<String>()
                    while (true) {
                        val inner = parser.next()
                        if (inner == XmlPullParser.END_TAG && parser.name == "channel") break
                        if (inner == XmlPullParser.START_TAG && parser.name == "display-name") parser.nextText().trim().takeIf { it.isNotBlank() }?.let(displayNames::add)
                    }
                    if (id.isNotBlank() && displayNames.isNotEmpty()) names[id] = displayNames
                }
                "programme" -> {
                    val id = parser.getAttributeValue(null, "channel").orEmpty()
                    val start = parseDate(parser.getAttributeValue(null, "start"))
                    val stop = parseDate(parser.getAttributeValue(null, "stop"))
                    var title = ""
                    while (true) {
                        val inner = parser.next()
                        if (inner == XmlPullParser.END_TAG && parser.name == "programme") break
                        if (inner == XmlPullParser.START_TAG && parser.name == "title") title = parser.nextText().trim()
                        else if (inner == XmlPullParser.START_TAG && parser.name == "desc") parser.nextText()
                    }
                    if (id.isNotBlank() && title.isNotBlank() && start != null && stop != null && stop > now && start < now + 48L * 60L * 60L * 1000L) {
                        programmes.getOrPut(id) { mutableListOf() }.add(Program(start, stop, title))
                    }
                }
            }
            event = parser.next()
        }
        return Dataset(names, programmes)
    }

    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()
        for (pattern in listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmmss")) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
                val normalized = if (pattern.endsWith(" Z") && value.length >= 20) value.take(14) + " " + value.substring(15).take(5) else value.take(14)
                return format.parse(normalized)?.time
            } catch (_: Exception) { }
        }
        return null
    }
}
