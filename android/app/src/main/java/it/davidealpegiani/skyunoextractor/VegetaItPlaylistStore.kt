package it.davidealpegiani.skyunoextractor

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

internal data class VegetaChannel(
    val name: String,
    val url: String,
    val logo: String = "",
    val category: String = "Vegeta IT",
    val serverId: String = "",
    val serverName: String = ""
)

/**
 * Archivio locale separato per i canali importati da Vegeta IT.
 * Non sovrascrive le playlist personali.
 */
internal object VegetaItPlaylistStore {
    private const val FILE_NAME = "davix-vegeta-it-playlist-v1.json"
    private const val MAX_FILE_BYTES = 48L * 1024L * 1024L

    private fun atomicFile(context: Context) = AtomicFile(File(context.filesDir, FILE_NAME))

    fun save(context: Context, channels: List<VegetaChannel>, catalogUpdatedAt: String? = null) {
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("section", "Vegeta IT")
            .put("catalogUpdatedAt", catalogUpdatedAt ?: JSONObject.NULL)
            .put("savedAt", System.currentTimeMillis())
            .put("channels", JSONArray().apply {
                channels.forEach { ch ->
                    put(
                        JSONObject()
                            .put("name", ch.name)
                            .put("url", ch.url)
                            .put("logo", ch.logo)
                            .put("category", ch.category.ifBlank { "Vegeta IT" })
                            .put("serverId", ch.serverId)
                            .put("serverName", ch.serverName)
                    )
                }
            })
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "Playlist Vegeta troppo grande." }
        val file = atomicFile(context)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            if (stream != null) file.failWrite(stream)
            throw error
        }
    }

    fun load(context: Context): List<VegetaChannel> {
        val file = atomicFile(context)
        if (!file.baseFile.exists()) return emptyList()
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_FILE_BYTES) { "Archivio Vegeta troppo grande." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
        if (root.optInt("schemaVersion") != 1) return emptyList()
        val array = root.optJSONArray("channels") ?: JSONArray()
        val result = ArrayList<VegetaChannel>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            val url = item.optString("url").trim()
            if (name.isBlank()) continue
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) continue
            result.add(
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
        return result
    }

    fun clear(context: Context) {
        atomicFile(context).delete()
    }

    fun count(context: Context): Int = load(context).size
}
