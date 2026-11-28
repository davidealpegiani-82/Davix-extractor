package it.davidealpegiani.skyunoextractor

/**
 * Raggruppa canali che rappresentano lo stesso programma con qualità/sorgenti diverse.
 * Es: "Rai 1 FHD", "Rai 1 HD", "Rai 1 SD" → chiave "rai 1".
 */
internal object ChannelGrouping {

    private val qualityTokens = Regex(
        "\\b(uhd|4k|fhd\\+?|full\\s*hd|hd\\+?|sd|hevc|h\\.?265|h\\.?264|hq|lq|low|high|raw)\\b",
        RegexOption.IGNORE_CASE
    )
    private val junkTokens = Regex("[\\[\\](){}|_./\\\\+\\-]+")
    private val multiSpace = Regex("\\s+")

    fun normalizeKey(name: String): String {
        var n = name.lowercase().trim()
        n = qualityTokens.replace(n, " ")
        n = junkTokens.replace(n, " ")
        n = multiSpace.replace(n, " ").trim()
        return n
    }

    fun qualityLabel(name: String): String {
        val found = qualityTokens.findAll(name).map {
            it.value.uppercase().replace(" ", "")
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
            val primary = channels[indices.first()]
            val baseName = primary.name
                .replace(qualityTokens, " ")
                .replace(junkTokens, " ")
                .replace(multiSpace, " ")
                .trim()
                .ifBlank { primary.name }
            Group(key = key, displayName = baseName, indices = indices)
        }
    }

    fun groupEntries(entries: List<IndexedValue<TvChannel>>): List<Group> {
        val order = LinkedHashMap<String, MutableList<Int>>()
        val names = LinkedHashMap<String, String>()
        for (entry in entries) {
            val key = normalizeKey(entry.value.name).ifBlank { "canale-${entry.index}" }
            order.getOrPut(key) { mutableListOf() }.add(entry.index)
            if (key !in names) {
                names[key] = entry.value.name
                    .replace(qualityTokens, " ")
                    .replace(junkTokens, " ")
                    .replace(multiSpace, " ")
                    .trim()
                    .ifBlank { entry.value.name }
            }
        }
        return order.map { (key, indices) ->
            Group(key = key, displayName = names[key] ?: key, indices = indices)
        }
    }
}
