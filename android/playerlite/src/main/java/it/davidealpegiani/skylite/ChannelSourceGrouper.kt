package it.davidealpegiani.skylite

/** A logical channel may expose multiple selectable provider URLs. */
internal data class CloudChannel(
    val name: String,
    val url: String,
    val logo: String = "",
    val category: String = "Non classificati",
    val tvgId: String = "",
    val tvgName: String = ""
)

internal data class ChannelSourceGroup(
    val key: String,
    val name: String,
    val sources: List<CloudChannel>
)

/**
 * Conservative channel identity matching.
 *
 * A provider tvg-id is metadata, not proof that two differently named
 * channels carry the same programme. Identity is therefore based on the
 * exact normalized channel name; only technical quality/codec labels are
 * removed. Distinctive words, numbers and suffixes (+1, Sport, Cinema, etc.)
 * are deliberately preserved.
 */
internal object ChannelSourceGrouper {
    private val episodePattern =
        Regex("""(?i)\\bS\\d{1,2}\\s*E\\d{1,3}\\b|\\b\\d{1,2}x\\d{1,3}\\b""")

    private val qualityPattern = Regex(
        """(?i)\\b(8k|4k|uhd|fhd|full\\s*hd|hd|sd|2160p|1080p|1080i|720p|576p|480p|hevc|h[ .]?265|x265|h[ .]?264|x264|hdr10\\+?|50\\s*fps|60\\s*fps)\\b"""
    )

    private val vodPathSegments = setOf(
        "vod", "movie", "movies", "film", "films",
        "series", "serie", "tvshows", "tv-shows", "episode", "episodes"
    )

    private fun cleanedName(name: String): String =
        name.lowercase(java.util.Locale.ROOT)
            .replace(qualityPattern, " ")
            .replace(Regex("[^\\\\p{L}\\\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun displayName(name: String): String =
        name.replace(qualityPattern, " ")
            .replace(Regex("""[\\s._-]+"""), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    fun group(items: List<CloudChannel>): List<ChannelSourceGroup> {
        val buckets = LinkedHashMap<String, MutableList<CloudChannel>>()
        items.forEach { channel ->
            val mediaKind = mediaKind(channel)
            val identity = cleanedName(channel.name)
            // Empty names must never collapse together. tvg-id is intentionally
            // not used as a fallback because providers often reuse identifiers.
            val identityKey = if (identity.isNotBlank()) {
                "name:$identity"
            } else {
                "url:" + channel.url.trim().lowercase(java.util.Locale.ROOT)
            }
            val key = "$mediaKind|$identityKey"
            buckets.getOrPut(key) { mutableListOf() }.add(channel)
        }
        return buckets.map { (key, sources) ->
            val uniqueSources = sources.distinctBy { it.url.trim() }
            val firstNamed = uniqueSources.firstOrNull { it.name.isNotBlank() }?.name.orEmpty()
            ChannelSourceGroup(
                key = key,
                name = displayName(firstNamed).ifBlank { firstNamed },
                sources = uniqueSources
            )
        }
    }

    private fun mediaKind(channel: CloudChannel): String {
        val categoryRoot = channel.category.substringBefore('>').trim()
            .lowercase(java.util.Locale.ROOT)
        val explicitVodCategory = categoryRoot == "vod"

        val path = runCatching {
            java.net.URI(channel.url).path.orEmpty()
        }.getOrElse {
            channel.url.substringBefore('?').substringBefore('#')
        }
        val pathSegments = path.lowercase(java.util.Locale.ROOT)
            .split('/')
            .filter { it.isNotBlank() }
        val vodPath = pathSegments.any { it in vodPathSegments }
        val episodicTitle = episodePattern.containsMatchIn(channel.name) ||
            episodePattern.containsMatchIn(channel.url)

        return if (explicitVodCategory || vodPath || episodicTitle) "vod" else "live"
    }
}
