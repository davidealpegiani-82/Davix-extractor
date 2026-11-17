package it.davidealpegiani.skylite

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object PlayerCloudSync {
    private const val PREFS = "sky_player_cloud_sync"
    private const val SECURE_PREFS = "sky_player_cloud_sync_secure"
    private const val KEY_ALIAS = "sky_player_device_token_v1"
    private const val BASE_URL = "base_url"
    private const val INSTALL_ID = "install_id"
    private const val TOKEN = "device_token"
    private const val EPG_SOURCE = "custom_epg_source"
    private const val VERSION = "config_version"
    private const val CONFIG = "config_json"
    private const val LEASE_EXPIRY = "lease_expires_at"

    fun baseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(BASE_URL, "https://sky-cloud-sync.davide-alpegiani.workers.dev").orEmpty().trim().trimEnd('/')

    fun saveBaseUrl(context: Context, value: String) {
        val normalized = value.trim().trimEnd('/')
        require(normalized.startsWith("https://", true)) { "Inserisci un indirizzo HTTPS valido." }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(BASE_URL, normalized).apply()
    }

    fun hasToken(context: Context): Boolean = !readToken(context).isNullOrBlank()

    fun hasValidLease(context: Context): Boolean =
        hasToken(context) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(LEASE_EXPIRY, 0L) > System.currentTimeMillis()

    fun clearAuthorization(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(CONFIG).remove(VERSION).remove(LEASE_EXPIRY).apply()
        context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE).edit().remove(TOKEN).remove(EPG_SOURCE).apply()
    }

    fun currentVersion(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(VERSION, 0)

    fun cachedConfig(context: Context): JSONObject? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CONFIG, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** The custom XMLTV URL contains account credentials and is never kept in plain config JSON. */
    fun customEpgSource(context: Context): String? {
        val packedValue = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
            .getString(EPG_SOURCE, null) ?: return null
        return runCatching {
            val packed = Base64.decode(packedValue, Base64.NO_WRAP)
            require(packed.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun saveCustomEpgSource(context: Context, value: String?) {
        val editor = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE).edit()
        if (value.isNullOrBlank()) {
            editor.remove(EPG_SOURCE)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            editor.putString(EPG_SOURCE, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
        }
        editor.apply()
    }

    fun activate(context: Context, codeValue: String): JSONObject {
        val code = codeValue.uppercase().filter { it.isLetterOrDigit() }
        require(code.matches(Regex("[A-HJ-NP-Z2-9]{10}"))) { "Inserisci il codice monouso di 10 caratteri." }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var installId = prefs.getString(INSTALL_ID, null)
        if (installId.isNullOrBlank()) {
            installId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(INSTALL_ID, installId).apply()
        }
        val token = getOrCreateToken(context)
        val body = JSONObject()
            .put("code", code)
            .put("deviceSecret", token)
            .put("installId", installId)
            .put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100))
            .put("platform", "android")
        val result = request(context, "POST", "/v2/activate", body, authenticated = false)
        val lease = result.optLong("leaseExpiresAt", 0L)
        require(result.optBoolean("activated") && lease > System.currentTimeMillis()) {
            "Il server non ha restituito una licenza valida."
        }
        prefs.edit().putLong(LEASE_EXPIRY, lease).apply()
        return result
    }

    fun sync(context: Context): JSONObject {
        val version = currentVersion(context)
        val manifest = request(context, "GET", "/v2/config/manifest?sinceVersion=$version", null, authenticated = true)
        val lease = manifest.optLong("leaseExpiresAt", 0L)
        require(lease > System.currentTimeMillis()) { "Licenza offline scaduta. Riconnetti il Player." }
        if (manifest.optBoolean("unchanged", false)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LEASE_EXPIRY, lease).apply()
            return JSONObject().put("unchanged", true).put("version", manifest.optInt("version", version))
        }

        val remoteVersion = manifest.optInt("version", 0)
        val channelCount = manifest.optInt("channelCount", -1)
        val chunkCount = manifest.optInt("chunkCount", -1)
        require(remoteVersion > 0 && channelCount in 1..10_000 && chunkCount in 1..125) {
            "Il manifesto Cloud Sync non è valido o supera il limite di 10.000 canali."
        }
        require(chunkCount == (channelCount + 79) / 80) {
            "Il numero di blocchi ricevuti non corrisponde alla lista dichiarata."
        }

        val channels = org.json.JSONArray()
        for (index in 0 until chunkCount) {
            val part = request(context, "GET", "/v2/config/chunk?version=$remoteVersion&index=$index", null, authenticated = true)
            require(part.optInt("version", -1) == remoteVersion && part.optInt("index", -1) == index) {
                "La sincronizzazione è cambiata durante il download. Riprova."
            }
            val batch = part.optJSONArray("channels")
                ?: throw IllegalStateException("Un blocco della lista canali è incompleto.")
            require(batch.length() in 1..80) { "Un blocco della lista canali non è valido." }
            for (item in 0 until batch.length()) channels.put(batch.getJSONObject(item))
        }
        require(channels.length() == channelCount) {
            "La lista ricevuta è incompleta: ${channels.length()} di $channelCount canali."
        }

        val categories = manifest.optJSONArray("categories") ?: org.json.JSONArray()
        require(categories.length() <= 100) { "La configurazione contiene troppe categorie." }
        for (index in 0 until categories.length()) {
            val category = categories.optString(index)
            require(category.isNotBlank() && category.length <= 80) { "La configurazione contiene una categoria non valida." }
        }
        val valid = (0 until channels.length()).count { index ->
            val channel = channels.optJSONObject(index) ?: return@count false
            val name = channel.optString("name")
            val stream = channel.optString("url")
            val category = channel.optString("category", "Non classificati")
            name.isNotBlank() && category.length <= 80 &&
                (stream.startsWith("https://", true) || stream.startsWith("http://", true))
        }
        require(valid == channels.length()) { "La configurazione contiene canali non validi." }

        val config = JSONObject()
            .put("schemaVersion", 1)
            .put("updateId", manifest.optString("updateId"))
            .put("categories", categories)
            .put("channels", channels)
        val customEpg = manifest.optString("epgSourceUrl")
            .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        saveCustomEpgSource(context, customEpg)

        // Commit locale solo dopo aver ricevuto e validato tutti i blocchi.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CONFIG, config.toString())
            .putInt(VERSION, remoteVersion)
            .putLong(LEASE_EXPIRY, lease)
            .apply()
        return JSONObject()
            .put("unchanged", false)
            .put("version", remoteVersion)
            .put("channelCount", channels.length())
            .put("chunkCount", chunkCount)
    }

    private fun request(
        context: Context,
        method: String,
        path: String,
        body: JSONObject?,
        authenticated: Boolean
    ): JSONObject {
        val base = baseUrl(context)
        require(base.startsWith("https://", true)) { "Configura prima l'indirizzo HTTPS di DAVIX Cloud Sync." }
        val token = if (authenticated) readToken(context) else null
        if (authenticated) require(!token.isNullOrBlank()) { "Questo Player non è ancora associato." }
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (authenticated) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = runCatching { JSONObject(raw) }.getOrElse {
                JSONObject().put("error", "Risposta non valida dal servizio (HTTP $code).")
            }
            if (code !in 200..299) {
                throw IllegalStateException(result.optString("error").ifBlank { "Errore HTTP $code" })
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun getOrCreateToken(context: Context): String {
        readToken(context)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        saveToken(context, token)
        return token
    }

    private fun readToken(context: Context): String? {
        val packedValue = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN, null) ?: return null
        return runCatching {
            val packed = Base64.decode(packedValue, Base64.NO_WRAP)
            require(packed.size > 12)
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun saveToken(context: Context, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val packed = cipher.iv + encrypted
        context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE).edit()
            .putString(TOKEN, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
