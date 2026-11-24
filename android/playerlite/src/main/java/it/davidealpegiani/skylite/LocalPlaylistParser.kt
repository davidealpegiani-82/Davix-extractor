package it.davidealpegiani.skylite

import java.util.Locale

/** Parses plain M3U playlists without executing scripts, resolvers or embedded actions. */
internal object LocalPlaylistParser {
    private const val MAX_CHANNELS = 20_000
    private val attributePattern = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(document: String): List<CloudChannel> {
        require(document.length <= 32 * 1024 * 1024) { "La playlist supera il limite di 32 MB." }
        val result = mutableListOf<CloudChannel>()
        val seen = mutableSetOf<String>()
        var pendingName = ""
        var pendingLogo = ""
        var pendingCategory = "Non classificati"
        var pendingTvgId = ""
        var pendingTvgName = ""

        for (rawLine in document.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val attributes = attributePattern.findAll(line)
                    .associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2].trim() }
                val comma = line.indexOf(',')
                pendingName = if (comma >= 0) line.substring(comma + 1).trim() else attributes["tvg-name"].orEmpty()
                pendingLogo = attributes["tvg-logo"].orEmpty()
                pendingCategory = attributes["group-title"].orEmpty().ifBlank { "Non classificati" }
                pendingTvgId = attributes["tvg-id"].orEmpty()
                pendingTvgName = attributes["tvg-name"].orEmpty()
                continue
            }
            if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                pendingCategory = line.substringAfter(':').trim().ifBlank { pendingCategory }
                continue
            }
            if (line.startsWith("#")) continue

            val url = line.substringBefore('|').trim()
            if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
                pendingName = ""
                pendingLogo = ""
                pendingCategory = "Non classificati"
                pendingTvgId = ""
                pendingTvgName = ""
                continue
            }
            val name = pendingName.ifBlank { pendingTvgName }.ifBlank { "Canale ${result.size + 1}" }
            val key = name.lowercase(Locale.ROOT) + "|" + url
            if (seen.add(key)) {
                result.add(CloudChannel(name, url, pendingLogo, pendingCategory, pendingTvgId, pendingTvgName))
                require(result.size <= MAX_CHANNELS) { "La playlist supera il limite di $MAX_CHANNELS canali." }
            }
            pendingName = ""
            pendingLogo = ""
            pendingCategory = "Non classificati"
            pendingTvgId = ""
            pendingTvgName = ""
        }
        require(result.isNotEmpty()) { "Nessun canale M3U valido trovato." }
        return result
    }
}
