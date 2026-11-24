package it.davidealpegiani.skylite

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/** Device-local playlist storage; no account, server, token or network sync is required. */
internal object LocalPlaylistStore {
    private const val FILE_NAME = "davix-local-playlist-v1.json"
    private const val MAX_FILE_BYTES = 32L * 1024L * 1024L

    private fun atomicFile(context: Context) = AtomicFile(File(context.filesDir, FILE_NAME))

    fun save(context: Context, channels: List<CloudChannel>) {
        require(channels.isNotEmpty()) { "La playlist è vuota." }
        val root = JSONObject().put("schemaVersion", 1).put("channels", JSONArray().apply {
            channels.forEach { channel ->
                put(JSONObject()
                    .put("name", channel.name)
                    .put("url", channel.url)
                    .put("logo", channel.logo)
                    .put("category", channel.category)
                    .put("tvgId", channel.tvgId)
                    .put("tvgName", channel.tvgName))
            }
        })
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "La playlist salvata supera il limite consentito." }
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

    fun load(context: Context): List<CloudChannel> {
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
                require(total <= MAX_FILE_BYTES) { "Archivio locale troppo grande." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
        require(root.optInt("schemaVersion") == 1) { "Versione archivio locale non supportata." }
        val array = root.optJSONArray("channels") ?: JSONArray()
        val result = ArrayList<CloudChannel>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            val url = item.optString("url").trim()
            if (name.isNotBlank() && (url.startsWith("https://", true) || url.startsWith("http://", true))) {
                result.add(CloudChannel(name, url, item.optString("logo"), item.optString("category").ifBlank { "Non classificati" }, item.optString("tvgId"), item.optString("tvgName")))
            }
        }
        return result
    }

    fun clear(context: Context) {
        atomicFile(context).delete()
    }
}
