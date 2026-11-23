package it.davidealpegiani.skyunoextractor

import java.text.Normalizer
import java.util.Locale

/** Conservative filter: explicit Italian metadata or known Italian broadcasters. */
internal object ItalianLiveChannelFilter {
    private val knownItalianChannel = Regex(
        """^(rai(?:\\b|\\s)|canale\\s*5\\b|italia\\s*1\\b|italia\\s*2\\b|rete\\s*4\\b|la\\s*7d?\\b|tv\\s*8\\b|tv\\s*2000\\b|cielo\\b|nove\\b|real\\s*time\\b|dmax\\b|giallo\\b|focus\\b|iris\\b|la\\s*5\\b|top\\s*crime\\b|cine\\s*34\\b|boing\\b|cartoonito\\b|tgcom\\s*24\\b|mediaset\\b|sky\\s*(uno|atlantic|cinema|sport|tg\\s*24|arte|serie|investigation|crime|nature|documentaries|family|max|comedy|showcase|witness|living|reality)\\b|telelombardia\\b|telenova\\b|antenna\\s*3\\b|telecity\\b|canale\\s*italia\\b|bergamo\\s*tv\\b|brescia\\s*tv\\b|tele\\s*norba\\b|radio\\s*italia\\s*tv\\b|rtl\\s*102\\.5\\s*tv\\b)""",
        RegexOption.IGNORE_CASE
    )

    fun isItalian(name: String, category: String, metadata: List<String> = emptyList()): Boolean {
        val evidence = listOf(category) + metadata
        if (evidence.any(::hasItalianMetadata)) return true
        if (name.contains("🇮🇹")) return true
        if (Regex("""(^|[\\s\\[({])(?:IT|ITA|IT-IT)(?:$|[\\s\\])}])""", RegexOption.IGNORE_CASE)
                .containsMatchIn(name)) return true
        return knownItalianChannel.containsMatchIn(normalize(name))
    }

    private fun hasItalianMetadata(raw: String): Boolean {
        val value = normalize(raw)
        return value in setOf("it", "ita", "it it", "ita it", "italia", "italy", "italian", "italiano", "italiana") ||
            Regex("""(^|[^a-z])(it|ita|it-it|ita-it|italia|italy|italian|italiano|italiana)([^a-z]|$)""")
                .containsMatchIn(value)
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9.]+"""), " ")
            .trim()
}
