package it.davidealpegiani.skyunoextractor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores IPTV source URLs encrypted with a key that never leaves Android Keystore. */
internal object IptvSourceSettings {
    private const val PREFS = "davix_iptv_sources_secure"
    private const val KEY_ALIAS = "davix_iptv_sources_aes_v1"
    private const val PLAYLIST = "playlist_url"
    private const val EPG = "epg_url"
    private const val MANDRAKODI = "mandrakodi_catalog_url"
    private const val MANDRAKODI_PASSWORD = "mandrakodi_password"
    private const val MANDRAKODI_DEVICE_ID = "mandrakodi_device_id"
    private const val MANDRAKODI_ADDON_VERSION = "mandrakodi_addon_version"
    private const val DEFAULT_MANDRAKODI_ADDON_VERSION = "2.2.1.e"

    fun playlistUrl(context: Context): String = read(context, PLAYLIST).orEmpty()
    fun epgUrl(context: Context): String = read(context, EPG).orEmpty()
    fun mandraKodiUrl(context: Context): String = read(context, MANDRAKODI).orEmpty()
    fun mandraKodiPassword(context: Context): String = read(context, MANDRAKODI_PASSWORD).orEmpty()
    fun mandraKodiDeviceId(context: Context): String = read(context, MANDRAKODI_DEVICE_ID).orEmpty()
    fun mandraKodiAddonVersion(context: Context): String = read(context, MANDRAKODI_ADDON_VERSION).orEmpty().ifBlank { DEFAULT_MANDRAKODI_ADDON_VERSION }

    fun saveMandraKodiAddonVersion(context: Context, version: String) {
        val clean = version.trim()
        require(clean.matches(Regex("[A-Za-z0-9._-]{1,32}"))) {
            "Versione add-on MandraKodi non valida."
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MANDRAKODI_ADDON_VERSION, encrypt(clean))
            .apply()
    }

    /** Imports only the MandraKodi values needed for its documented HTTP User-Agent. */
    fun saveMandraKodiCredentials(context: Context, password: String, deviceId: String) {
        val cleanPassword = password.trim()
        val cleanDeviceId = deviceId.trim().uppercase()
        require(cleanPassword.isNotBlank() && cleanPassword.length <= 512) {
            "Nel file Kodi non è stata trovata una password MandraKodi valida."
        }
        require(cleanDeviceId.matches(Regex("[A-Z0-9]{6}"))) {
            "L'identificativo MandraKodi deve contenere 6 caratteri."
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MANDRAKODI_PASSWORD, encrypt(cleanPassword))
            .putString(MANDRAKODI_DEVICE_ID, encrypt(cleanDeviceId))
            .apply()
    }

    fun mandraKodiUserAgent(context: Context): String {
        val password = mandraKodiPassword(context)
        val deviceId = mandraKodiDeviceId(context)
        require(password.isNotBlank() && deviceId.matches(Regex("[A-Z0-9]{6}"))) {
            "Importa prima le impostazioni MandraKodi da Kodi."
        }
        return "MandraKodi2@@${mandraKodiAddonVersion(context)}@@$password@@$deviceId"
    }

    fun save(context: Context, playlistUrl: String, epgUrl: String, mandraKodiCatalogUrl: String = mandraKodiUrl(context)) {
        val playlist = validate(playlistUrl, "M3U")
        val epg = validate(epgUrl, "XMLTV")
        val mandraKodi = validate(mandraKodiCatalogUrl, "catalogo MandraKodi")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PLAYLIST, playlist.takeIf { it.isNotBlank() }?.let(::encrypt))
            .putString(EPG, epg.takeIf { it.isNotBlank() }?.let(::encrypt))
            .putString(MANDRAKODI, mandraKodi.takeIf { it.isNotBlank() }?.let(::encrypt))
            .apply()
    }

    private fun validate(raw: String, label: String): String {
        val value = raw.trim()
        require(value.isEmpty() || value.length <= 8192) { "L'indirizzo $label è troppo lungo." }
        if (value.isNotEmpty()) {
            val uri = android.net.Uri.parse(value)
            require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
                "L'indirizzo $label deve iniziare con http:// oppure https://."
            }
            require(!uri.host.isNullOrBlank()) { "L'indirizzo $label non è valido." }
        }
        return value
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun read(context: Context, name: String): String? {
        val packedValue = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(name, null) ?: return null
        return runCatching {
            val packed = Base64.decode(packedValue, Base64.NO_WRAP)
            require(packed.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
