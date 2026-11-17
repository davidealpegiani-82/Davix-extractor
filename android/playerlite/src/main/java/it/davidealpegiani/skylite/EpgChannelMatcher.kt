package it.davidealpegiani.skylite

import java.util.Locale

/** Deterministic XMLTV matcher. Fuzzy matching fails closed on weak or ambiguous results. */
internal object EpgChannelMatcher {
    fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("\\b(full|hd|fhd|uhd|4k|hevc|h\\.?265|h\\.?264|1080p|720p|sd)\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}+]+"), " ")
        .trim().replace(Regex("\\s+"), " ")

    fun similarity(a: String, b: String): Double {
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

    fun findMatch(
        tvgId: String, tvgName: String, channelName: String,
        candidates: Map<String, List<String>>, cachedId: String? = null,
        minimumScore: Double = 0.92, minimumMargin: Double = 0.10
    ): String? {
        val ids = candidates.keys
        if (!cachedId.isNullOrBlank()) ids.firstOrNull { it.equals(cachedId, true) }?.let { return it }
        if (tvgId.isNotBlank()) ids.firstOrNull { it.equals(tvgId, true) }?.let { return it }
        val targets = listOf(tvgName, channelName).map(::normalize).filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return null
        val exact = candidates.filterValues { names -> names.any { normalize(it) in targets } }.keys.toList()
        if (exact.size == 1) return exact.first()
        if (exact.size > 1) return null
        val scored = candidates.map { (id, names) ->
            id to (names.maxOfOrNull { candidate ->
                targets.maxOfOrNull { target -> similarity(target, normalize(candidate)) } ?: 0.0
            } ?: 0.0)
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val runnerUp = scored.getOrNull(1)?.second ?: 0.0
        return best.first.takeIf { best.second >= minimumScore && best.second - runnerUp >= minimumMargin }
    }
}
