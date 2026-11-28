package it.davidealpegiani.skyunoextractor

/**
 * Raggruppa canali che rappresentano lo stesso programma con qualità/sorgenti diverse.
 * Es: "Rai 1 FHD", "Rai 1 HD", "Rai 1 1080p" → chiave "rai 1".
 *
 * Allineato alla logica conservativa del Player (ChannelSourceGrouper):
 * toglie solo etichette tecniche di qualità/codec, non brand o numeri significativi.
 */
internal object ChannelGrouping {

    private val qualityTokens = Regex(
        """(?i)\b(""" +
            """8k|4k|uhd|fhd\+?|full\s*hd|hd\+?|sd|hq|lq|""" +
            """2160p|1080p|1080i|720p|576p|480p|""" +
            """hevc|h[ .]?265|x265|h[ .]?264|x264|""" +
            """hdr10\+?|hdr|50\s*fps|60\s*fps|""" +
            """raw|low|high""" +
            """)\b"""
    )
    private val junkTokens = Regex("""[\[\](){}|_./\\+\-–—]+""")
    private val multiSpace = Regex("""\s+""")

    fun normalizeKey(name: String): String {
        var n = name.lowercase().trim()
        n = qualityTokens.replace(n, " ")
        n = junkTokens.replace(n, " ")
        n = multiSpace.replace(n, " ").trim()
        return n
    }

    fun qualityLabel(name: String): String {
        val found = qualityTokens.findAll(name).map {
            it.value.uppercase().replace(Regex("""\s+"""), "").replace(".", "")
        }.toList()
        if (found.isEmpty()) return "Standard"
        return found.distinct().joinToString(" · ")
    }

    data class Group(
        val key: String,
        val displayName: String,
        val indices: List<Int>
    )

    fun groupIndexed(channels: List<TvChannel>): List<Group> {
        val order = LinkedHashMap<String, MutableList<Int>>()
        channels.forEachIndexed { index, channel ->
            val key = normalizeKey(channel.name).ifBlank { "canale-$index" }
            order.getOrPut(key) { mutableListOf() }.add(index)
        }
        return order.map { (key, indices) ->
            Group(key = key, displayName = displayNameFor(channels[indices.first()].name), indices = indices)
        }
    }

    fun groupEntries(entries: List<IndexedValue<TvChannel>>): List<Group> {
        val order = LinkedHashMap<String, MutableList<Int>>()
        val names = LinkedHashMap<String, String>()
        val urlsSeenInKey = HashMap<String, MutableSet<String>>()
        for (entry in entries) {
            val key = normalizeKey(entry.value.name).ifBlank { "canale-${entry.index}" }
            val urlKey = entry.value.url.trim().lowercase()
            val seen = urlsSeenInKey.getOrPut(key) { mutableSetOf() }
            if (urlKey.isNotEmpty() && !seen.add(urlKey)) {
                continue
            }
            order.getOrPut(key) { mutableListOf() }.add(entry.index)
            if (key !in names) {
                names[key] = displayNameFor(entry.value.name)
            }
        }
        return order.map { (key, indices) ->
            Group(key = key, displayName = names[key] ?: key, indices = indices)
        }
    }

    private fun displayNameFor(name: String): String =
        name.replace(qualityTokens, " ")
            .replace(junkTokens, " ")
            .replace(multiSpace, " ")
            .trim()
            .ifBlank { name }
}
