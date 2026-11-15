package it.davidealpegiani.skylite

import android.content.Context
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

internal data class EpgChannelInput(val name: String, val url: String, val tvgId: String = "", val tvgName: String = "")
internal data class EpgGuide(val nowTitle: String = "", val nextTitle: String = "", val nowStartMs: Long = 0L, val nowStopMs: Long = 0L, val epgId: String = "")
internal data class EpgRefreshResult(val guides: Map<String, EpgGuide>, val matchedCount: Int, val sourceLabel: String)

internal object EpgGuideManager {
    private const val PREFS = "davix_epg_cache"
    private const val LAST_SYNC = "last_sync"
    private const val LAST_ATTEMPT = "last_attempt"
    private const val MATCHED_COUNT = "matched_count"
    private const val GUIDE_CACHE = "guide_cache"
    private const val MATCH_CACHE = "match_cache"
    private const val CHANNEL_SIGNATURE = "channel_signature"
    private const val REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val NO_MATCH_RETRY_INTERVAL_MS = 30L * 60L * 1000L

    // Limiti applicati dopo la decompressione: una sorgente XMLTV malformata
    // o sproporzionata non deve poter esaurire la memoria di Android TV.
    private const val MAX_DECOMPRESSED_BYTES = 48L * 1024L * 1024L
    private const val MAX_CHANNELS = 20_000
    private const val MAX_PROGRAMMES = 300_000
    private const val MAX_PROGRAMMES_PER_CHANNEL = 2_000
    private const val MAX_DISPLAY_NAME_LENGTH = 160
    private const val MAX_PROGRAMME_TITLE_LENGTH = 300

    // Fonti indipendenti: la guida Sky ha priorità sui canali Sky; le altre
    // fonti completano i canali e gli orari mancanti. I download partono insieme.
    private val sources = listOf(
        "Sky Italia" to "https://iptv-org.github.io/epg/guides/it/guidatv.sky.it.xml",
        "IPTV-EPG Italia" to "https://iptv-epg.org/files/epg-it.xml.gz",
        "EPG Share Italia" to "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz",
        "Open EPG Italia 3" to "https://www.open-epg.com/files/italy3.xml.gz",
        "Open EPG Italia 8" to "https://www.open-epg.com/files/italy8.xml.gz"
    )

    private data class Program(val start: Long, val stop: Long, val title: String)

    private class LimitedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
        private var bytesRead = 0L

        private fun account(count: Int): Int {
            if (count > 0) {
                bytesRead += count
                if (bytesRead > maxBytes) {
                    throw IllegalStateException("La guida XMLTV supera il limite di dimensione consentito.")
                }
            }
            return count
        }

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            account(super.read(buffer, offset, length))
    }
    private data class Dataset(val channelNames: Map<String, List<String>>, val programs: Map<String, List<Program>>)

    fun shouldRefresh(context: Context, channels: List<EpgChannelInput>): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val signature = channels.map { normalize(it.tvgId) + "|" + normalize(it.tvgName.ifBlank { it.name }) }
            .sorted().joinToString("\n")
        val now = System.currentTimeMillis()
        val signatureChanged = prefs.getString(CHANNEL_SIGNATURE, null) != signature
        val matchedCount = prefs.getInt(MATCHED_COUNT, 0)
        return signatureChanged || if (matchedCount > 0) {
            now - prefs.getLong(LAST_SYNC, 0L) >= REFRESH_INTERVAL_MS
        } else {
            now - prefs.getLong(LAST_ATTEMPT, 0L) >= NO_MATCH_RETRY_INTERVAL_MS
        }
    }

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

        // Avvia tutte le fonti in parallelo, con massimo 3 download/parse simultanei
        // per non saturare memoria e connessione sui dispositivi Android TV meno potenti.
        val executor = Executors.newFixedThreadPool(minOf(3, sources.size))
        val futures = sources.map { (label, address) ->
            executor.submit(Callable { label to download(address) })
        }
        val datasets = mutableListOf<Pair<String, Dataset>>()
        try {
            futures.forEach { future ->
                runCatching { future.get() }.onSuccess { (label, dataset) ->
                    if (dataset.channelNames.isNotEmpty()) datasets.add(label to dataset)
                }
            }
        } finally {
            executor.shutdownNow()
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
            val channelCandidates = datasets.mapNotNull { (label, dataset) ->
                val cachedId = oldMatches.optString(baseKey + "|" + label).takeIf { it.isNotBlank() }
                val id = findMatch(channel, dataset, cachedId) ?: return@mapNotNull null
                val programmes = dataset.programs[id].orEmpty().filter { it.stop > now }
                newMatches.put(baseKey + "|" + label, id)
                Triple(label, id, programmes)
            }
            if (channelCandidates.isEmpty()) continue

            // Unisce i palinsesti: Sky Italia resta prioritaria, le altre fonti
            // riempiono i buchi. A parità di fascia oraria vince la prima fonte.
            val mergedPrograms = channelCandidates.flatMap { it.third }
                .sortedWith(compareBy<Program> { it.start }.thenBy { it.stop })
                .distinctBy { it.start to it.stop }
            val current = mergedPrograms.firstOrNull { it.start <= now && it.stop > now }
            val next = mergedPrograms.firstOrNull { it.start > now }

            channelCandidates.forEach { (label, _, _) -> usedSources.add(label) }
            val preferredId = channelCandidates.firstOrNull { it.first == "Sky Italia" }?.second
                ?: channelCandidates.first().second
            result[channel.url] = EpgGuide(
                current?.title.orEmpty(),
                next?.title.orEmpty(),
                current?.start ?: 0L,
                current?.stop ?: 0L,
                preferredId
            )
            matched++
        }

        val cache = JSONObject()
        result.forEach { (url, guide) ->
            cache.put(url, JSONObject().put("now", guide.nowTitle).put("next", guide.nextTitle).put("start", guide.nowStartMs).put("stop", guide.nowStopMs).put("id", guide.epgId))
        }
        val signature = channels.map { normalize(it.tvgId) + "|" + normalize(it.tvgName.ifBlank { it.name }) }
            .sorted().joinToString("\n")
        val editor = prefs.edit()
            .putString(GUIDE_CACHE, cache.toString())
            .putString(MATCH_CACHE, newMatches.toString())
            .putString(CHANNEL_SIGNATURE, signature)
            .putLong(LAST_ATTEMPT, now)
            .putInt(MATCHED_COUNT, matched)
        if (matched > 0) {
            editor.putLong(LAST_SYNC, now)
        } else {
            editor.remove(LAST_SYNC)
        }
        editor.apply()
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
        .replace(Regex("\\b(full|hd|fhd|uhd|4k|hevc|h\\.?265|h\\.?264|1080p|720p|sd)\\b"), " ")
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
            val decompressed: InputStream = if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
            LimitedInputStream(decompressed, MAX_DECOMPRESSED_BYTES).use { return parse(it) }
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
        var programmeCount = 0
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
                    if (id.isNotBlank() && displayNames.isNotEmpty()) {
                        if (id !in names && names.size >= MAX_CHANNELS) {
                            throw IllegalStateException("La guida XMLTV contiene troppi canali.")
                        }
                        names[id] = displayNames.take(20).map { it.take(MAX_DISPLAY_NAME_LENGTH) }
                    }
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
                        val channelProgrammes = programmes.getOrPut(id) { mutableListOf() }
                        if (channelProgrammes.size < MAX_PROGRAMMES_PER_CHANNEL) {
                            programmeCount++
                            if (programmeCount > MAX_PROGRAMMES) {
                                throw IllegalStateException("La guida XMLTV contiene troppi programmi.")
                            }
                            channelProgrammes.add(Program(start, stop, title.take(MAX_PROGRAMME_TITLE_LENGTH)))
                        }
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