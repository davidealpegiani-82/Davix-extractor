package it.davidealpegiani.skyunoextractor

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class VegetaImportResult(
    val serversTried: Int,
    val serversOk: Int,
    val channelsImported: Int,
    val errors: List<String>
)

/**
 * Importa i server Vegeta IT (solo status=up) in una sezione separata.
 * Non applica ancora il filtro italiano: viene fatto in un secondo momento.
 */
internal object VegetaItImporter {
    private const val MAX_M3U_BYTES = 24 * 1024 * 1024

    fun importAll(
        context: Context,
        progress: (String) -> Unit = {}
    ): VegetaImportResult {
        val catalog = VegetaItCatalogClient.download(progress)
        val upServers = catalog.servers.filter { it.status == "up" }
        if (upServers.isEmpty()) {
            throw IOException("Nessun server Vegeta IT attualmente online.")
        }

        val allChannels = ArrayList<VegetaChannel>()
        val errors = ArrayList<String>()
        var ok = 0

        upServers.forEachIndexed { index, server ->
            progress("Server ${index + 1}/${upServers.size}: ${server.name}…")
            try {
                val m3u = downloadM3u(server.url)
                val parsed = parseM3u(m3u, server)
                allChannels.addAll(parsed)
                ok++
                progress("${server.name}: ${parsed.size} canali")
            } catch (error: Exception) {
                val msg = server.name + ": " + (error.message ?: "errore sconosciuto")
                errors.add(msg)
                progress(msg)
            }
        }

        // Dedup per URL
        val unique = LinkedHashMap<String, VegetaChannel>()
        for (ch in allChannels) {
            val key = ch.url.trim().lowercase()
            if (key.isNotEmpty() && !unique.containsKey(key)) {
                unique[key] = ch
            }
        }
        val finalList = unique.values.toList()
        VegetaItPlaylistStore.save(context, finalList, catalog.updatedAt)
        progress("Importazione completata: ${finalList.size} canali unici.")

        return VegetaImportResult(
            serversTried = upServers.size,
            serversOk = ok,
            channelsImported = finalList.size,
            errors = errors
        )
    }

    private fun downloadM3u(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", "DAVIX-Extractor/1.0")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_M3U_BYTES) throw IOException("M3U troppo grande")
                    output.write(buffer, 0, read)
                }
            }
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseM3u(content: String, server: VegetaItServer): List<VegetaChannel> {
        val result = ArrayList<VegetaChannel>()
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var pendingName = "Canale"
        var pendingLogo = ""
        var pendingGroup = "Vegeta IT"

        for (line in lines) {
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                pendingName = extractName(line)
                pendingLogo = extractAttr(line, "tvg-logo")
                pendingGroup = extractAttr(line, "group-title").ifBlank { "Vegeta IT" }
            } else if (!line.startsWith("#") &&
                (line.startsWith("http://", true) || line.startsWith("https://", true))
            ) {
                result.add(
                    VegetaChannel(
                        name = pendingName.ifBlank { "Canale" },
                        url = line,
                        logo = pendingLogo,
                        category = pendingGroup,
                        serverId = server.id,
                        serverName = server.name
                    )
                )
                pendingName = "Canale"
                pendingLogo = ""
                pendingGroup = "Vegeta IT"
            }
        }
        return result
    }

    private fun extractName(extinf: String): String {
        val comma = extinf.lastIndexOf(',')
        if (comma >= 0 && comma < extinf.length - 1) {
            return extinf.substring(comma + 1).trim().ifBlank { "Canale" }
        }
        return "Canale"
    }

    private fun extractAttr(line: String, key: String): String {
        val regex = Regex("""$key=\"([^\"]*)\"""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }
}
