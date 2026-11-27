package it.davidealpegiani.skyunoextractor

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class VegetaItServer(
    val id: String,
    val name: String,
    val status: String,
    val url: String,
    val kind: String,
    val lastChecked: String?
)

internal data class VegetaItCatalog(
    val updatedAt: String?,
    val sourceGeneratedAt: String?,
    val servers: List<VegetaItServer>
)

/**
 * Scarica il catalogo centralizzato dei server italiani Vegeta da GitHub.
 * Non tocca le playlist personali.
 */
internal object VegetaItCatalogClient {
    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/davidealpegiani-82/Davix-extractor/main/catalogs/it/vegeta-it-servers.json"
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun download(progress: (String) -> Unit = {}): VegetaItCatalog {
        progress("Scarico catalogo Vegeta IT…")
        val body = request(CATALOG_URL)
        val root = try {
            JSONObject(body)
        } catch (error: Exception) {
            throw IOException("Catalogo Vegeta non valido (JSON).", error)
        }
        if (root.optInt("schemaVersion") != 1) {
            throw IOException("Versione schema catalogo Vegeta non supportata.")
        }
        val array = root.optJSONArray("servers")
            ?: throw IOException("Campo servers assente nel catalogo Vegeta.")
        val servers = ArrayList<VegetaItServer>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("name").trim().ifBlank { id }
            val status = item.optString("status").trim().lowercase()
            val url = item.optString("url").trim()
            if (id.isBlank() || url.isBlank()) continue
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) continue
            servers.add(
                VegetaItServer(
                    id = id,
                    name = name,
                    status = if (status == "up") "up" else "down",
                    url = url,
                    kind = item.optString("kind").ifBlank { "xtream" },
                    lastChecked = item.optString("lastChecked").takeIf { it.isNotBlank() }
                )
            )
        }
        progress("Catalogo Vegeta: ${servers.size} server.")
        return VegetaItCatalog(
            updatedAt = root.optString("updatedAt").takeIf { it.isNotBlank() },
            sourceGeneratedAt = root.optString("sourceGeneratedAt").takeIf { it.isNotBlank() },
            servers = servers
        )
    }

    private fun request(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DAVIX-Extractor/1.0")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Errore scaricamento catalogo Vegeta (HTTP $code).")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) throw IOException("Catalogo Vegeta troppo grande.")
                    output.write(buffer, 0, read)
                }
            }
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}
