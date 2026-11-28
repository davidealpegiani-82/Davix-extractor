package it.davidealpegiani.skyunoextractor

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class VegetaImportResult(
    val serversTried: Int,
    val serversOk: Int,
    val channelsImported: Int,
    val errors: List<String>,
    val source: String = "catalog"
)

/**
 * Importa i canali Vegeta IT.
 *
 * Il catalogo canali aggregato viene usato come base rapida, ma NON sostituisce
 * la scansione del catalogo server: ogni server pubblicato nel catalogo IT viene
 * provato, anche quando Vegeta lo marca temporaneamente "down". Lo stato è
 * informativo e non deve diventare un filtro che nasconde server al client.
 */
internal object VegetaItImporter {
    private const val CHANNELS_CATALOG_URL =
        "https://raw.githubusercontent.com/davidealpegiani-82/Davix-extractor/main/catalogs/it/vegeta-it-channels.json"
    private const val MAX_CATALOG_BYTES = 8 * 1024 * 1024
    private const val MAX_M3U_BYTES = 80 * 1024 * 1024

    fun importAll(
        context: Context,
        progress: (String) -> Unit = {}
    ): VegetaImportResult {
        val allChannels = ArrayList<VegetaChannel>()
        val errors = ArrayList<String>()
        var catalogUpdatedAt: String? = null

        // 1) Catalogo aggregato: accelera l'importazione e conserva i canali
        // già raccolti dal job automatico, ma non evita la scansione live.
        try {
            progress("Scarico catalogo canali Vegeta IT…")
            val body = request(CHANNELS_CATALOG_URL, MAX_CATALOG_BYTES, "application/json")
            val root = JSONObject(body)
            if (root.optInt("schemaVersion") != 1) {
                throw IOException("Schema catalogo canali non supportato.")
            }
            val array = root.optJSONArray("channels")
                ?: throw IOException("Campo channels assente.")
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val url = item.optString("url").trim()
                if (name.isBlank()) continue
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) continue
                allChannels.add(
                    VegetaChannel(
                        name = name,
                        url = url,
                        logo = item.optString("logo"),
                        category = item.optString("category").ifBlank { "Vegeta IT" },
                        serverId = item.optString("serverId"),
                        serverName = item.optString("serverName")
                    )
                )
            }
            catalogUpdatedAt = root.optString("updatedAt").takeIf { it.isNotBlank() }
            progress("Catalogo aggregato: ${allChannels.size} canali iniziali.")
        } catch (error: Exception) {
            val message = "catalogo canali: ${error.message ?: "errore sconosciuto"}"
            errors.add(message)
            progress(message)
        }

        // 2) Legge l'intero elenco server. Non filtrare per status=up:
        // il dato può essere transitorio o non aggiornato quando parte DAVIX.
        val serverCatalog = try {
            VegetaItCatalogClient.download(progress)
        } catch (error: Exception) {
            val message = "catalogo server: ${error.message ?: "errore sconosciuto"}"
            errors.add(message)
            if (allChannels.isEmpty()) {
                throw IOException(
                    "Impossibile leggere i cataloghi Vegeta." +
                        " Dettagli: ${errors.joinToString(" · ")}",
                    error
                )
            }
            progress("Scansione live non disponibile; uso i canali aggregati.")
            null
        }

        var serversOk = 0
        val serversTried = serverCatalog?.servers?.size ?: 0
        if (serverCatalog != null) {
            if (serverCatalog.servers.isEmpty()) {
                errors.add("Il catalogo server non contiene server IT.")
            }
            serverCatalog.servers.forEachIndexed { index, server ->
                val state = if (server.status == "up") "online" else "segnalato offline"
                progress("Server ${index + 1}/${serversTried}: ${server.name} (${state})…")
                try {
                    // Ogni server del catalogo viene tentato, indipendentemente
                    // dallo stato pubblicato da Vegeta.
                    val m3u = downloadM3u(server.url)
                    val parsed = parseM3u(m3u, server)
                    if (parsed.isEmpty()) {
                        val message = "${server.name}: playlist vuota"
                        errors.add(message)
                        progress(message)
                    } else {
                        allChannels.addAll(parsed)
                        serversOk++
                        progress("${server.name}: ${parsed.size} canali")
                    }
                } catch (error: Exception) {
                    val message = "${server.name}: ${error.message ?: "errore sconosciuto"}"
                    errors.add(message)
                    progress(message)
                }
            }
            catalogUpdatedAt = serverCatalog.updatedAt ?: catalogUpdatedAt
        }

        val unique = dedupe(allChannels)
        if (unique.isEmpty()) {
            throw IOException(
                "Nessun canale recuperato dai cataloghi o dai server Vegeta." +
                    if (errors.isNotEmpty()) " Dettagli: " + errors.take(5).joinToString(" · ") else ""
            )
        }

        VegetaItPlaylistStore.save(context, unique, catalogUpdatedAt)
        progress(
            "Importazione completata: ${unique.size} canali unici; " +
                "${serversOk}/${serversTried} server con playlist valida."
        )
        return VegetaImportResult(
            serversTried = serversTried,
            serversOk = serversOk,
            channelsImported = unique.size,
            errors = errors,
            source = if (serverCatalog == null) "catalog" else "catalog+live"
        )
    }

    private fun dedupe(channels: List<VegetaChannel>): List<VegetaChannel> {
        val unique = LinkedHashMap<String, VegetaChannel>()
        for (ch in channels) {
            val key = ch.url.trim().lowercase()
            if (key.isNotEmpty() && !unique.containsKey(key)) {
                unique[key] = ch
            }
        }
        return unique.values.toList()
    }

    private fun downloadM3u(address: String): String =
        request(address, MAX_M3U_BYTES, "*/*")

    private fun request(address: String, maxBytes: Int, accept: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "DAVIX-Extractor/1.0")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            val output = ByteArrayOutputStream()
            val input = connection.inputStream
            input.use {
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw IOException("Risposta troppo grande")
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
        var pendingName = "Canale"
        var pendingLogo = ""
        var pendingGroup = "Vegeta IT"
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
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
        val regex = Regex("""$key="([^"]*)""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }
}
