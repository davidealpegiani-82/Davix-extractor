package it.davidealpegiani.skylite

/**
 * A single logical channel can expose multiple provider URLs (quality, mirrors,
 * or different group-title values). Keep those sources selectable as variants.
 */
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
 * Groups only channels with a stable shared identity, while keeping linear
 * channels separate from on-demand titles.
 *
 * Important: words such as "Serie" or "Cinema" in a linear channel brand
 * (e.g. Sky Serie / Sky Cinema) are not sufficient evidence of VOD.
 */
internal object ChannelSourceGrouper {
    private val episodePattern =
        Regex("""(?i)\bS\d{1,2}\s*E\d{1,3}\b|\b\d{1,2}x\d{1,3}\b""")

    private val qualityPattern = Regex(
        """(?i)\\b(8k|4k|uhd|fhd|full\\s*hd|hd|sd|2160p|1080p|1080i|720p|576p|480p|hevc|h[ .]?265|x265|h[ .]?264|x264|hdr10\\+?|50\\s*fps|60\\s*fps)\\b"""
    )

    private val vodPathSegments = setOf(
        "vod", "movie", "movies", "film", "films",
        "series", "serie", "tvshows", "tv-shows", "episode", "episodes"
    )

    fun group(items: List<CloudChannel>): List<ChannelSourceGroup> {
        val buckets = LinkedHashMap<String, MutableList<CloudChannel>>()
        items.forEach { channel ->
            val mediaKind = mediaKind(channel)
            val identity = channel.tvgId.trim().takeIf { it.isNotBlank() }
                ?.let { "id:" + it.lowercase(java.util.Locale.ROOT) }
                ?: run {
                    val normalizedName = channel.name.lowercase(java.util.Locale.ROOT)
                        .replace(qualityPattern, " ")
                        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                        .trim()
                        .replace(Regex("\\s+"), " ")
                    if (normalizedName.isNotBlank()) {
                        "name:$normalizedName"
                    } else {
                        // Avoid merging every unnamed channel into one empty-name group.
                        "url:" + channel.url.trim().lowercase(java.util.Locale.ROOT)
                    }
                }
            val key = "$mediaKind|$identity"
            buckets.getOrPut(key) { mutableListOf() }.add(channel)
        }
        return buckets.map { (key, sources) ->
            ChannelSourceGroup(key, sources.first().name, sources.distinctBy { it.url })
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
