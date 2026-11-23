package it.davidealpegiani.skylite

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

internal class DavixUpdater(context: Context, private val appKey: String) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("davix_app_updater", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    @Volatile private var activeActivity: Activity? = null
    private var activeDownloadId = -1L
    private var receiverRegistered = false
    private var checkedThisProcess = false

    @Volatile private var activityResumed = false
    private var downloadStartInProgress = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != prefs.getLong("pending_download_id", -1L)) return

            executor.execute {
                val status = runCatching {
                    val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
                    }
                }.getOrNull() ?: return@execute

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    prefs.edit().putBoolean("download_completed", true).apply()
                }
                main.post {
                    if (id != prefs.getLong("pending_download_id", -1L)) return@post
                    val current = activeActivity
                    if (!activityResumed || current == null || current.isFinishing || current.isDestroyed) return@post
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        resumePendingInstall(current)
                    } else {
                        Toast.makeText(current, "Download aggiornamento non riuscito", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }



    fun onActivityResumed(activity: Activity) {
        activeActivity = activity
        activityResumed = true
        check(activity)
    }

    fun onActivityPaused(activity: Activity) {
        if (activeActivity === activity) activityResumed = false
    }

    fun check(activity: Activity) {
        activeActivity = activity
        if (!receiverRegistered) {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") app.registerReceiver(downloadReceiver, filter)
            receiverRegistered = true
        }

        // Riprende il download solo dopo aver verificato che la versione richiesta non sia già installata.
        executor.execute {
            try {
                val cleaned = clearCompletedInstallIfApplied()
                if (!cleaned) resumePendingInstall(activity)

                val now = System.currentTimeMillis()
                val lastCheck = prefs.getLong("last_version_check_at", 0L)
                if (now - lastCheck < CHECK_INTERVAL_MS || hasPendingDownload()) return@execute
                prefs.edit().putLong("last_version_check_at", now).apply()

                val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val manifest = try {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
                val item = JSONObject(manifest).getJSONObject(appKey)
                val latestCode = item.getLong("versionCode")
                val latestName = item.getString("versionName")
                val apkUrl = item.getString("apkUrl")
                require(Uri.parse(apkUrl).scheme == "https") { "URL APK non sicuro" }

                val currentCode = if (Build.VERSION.SDK_INT >= 28) {
                    app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    app.packageManager.getPackageInfo(app.packageName, 0).versionCode.toLong()
                }
                if (latestCode > currentCode) {
                    main.post {
                        val current = activeActivity ?: return@post
                        if (!activityResumed || current.isFinishing || current.isDestroyed) return@post
                        Toast.makeText(
                            current,
                            "DAVIX $latestName disponibile: download automatico avviato",
                            Toast.LENGTH_LONG
                        ).show()
                        downloadUpdate(current, latestName, apkUrl, latestCode)
                    }
                }
            } catch (_: Exception) {
                prefs.edit().putLong("last_version_check_at", 0L).apply()
            }
        }
    }



    private fun clearCompletedInstallIfApplied(): Boolean {
        val targetCode = prefs.getLong("pending_version_code", -1L)
        if (targetCode <= 0L) return false
        val currentCode = runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)
        if (currentCode < targetCode) return false

        val staleId = prefs.getLong("pending_download_id", -1L)
        if (staleId > 0L) {
            // Un download manuale della versione già installata non va cancellato mentre è ancora in corso.
            val downloadStatus = runCatching {
                val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.query(DownloadManager.Query().setFilterById(staleId)).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
                }
            }.getOrNull()
            if (downloadStatus == DownloadManager.STATUS_PENDING ||
                downloadStatus == DownloadManager.STATUS_RUNNING ||
                downloadStatus == DownloadManager.STATUS_PAUSED) return false
            runCatching {
                (app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(staleId)
            }
        }
        prefs.edit()
            .remove("pending_download_id")
            .remove("pending_apk_path")
            .remove("pending_version_code")
            .putBoolean("installer_launched", false)
            .putBoolean("download_completed", false)
            .putBoolean("awaiting_install_permission", false)
            .putLong("last_version_check_at", 0L)
            .apply()
        activeDownloadId = -1L
        return true
    }

    private fun hasPendingDownload(): Boolean {
        val id = prefs.getLong("pending_download_id", -1L)
        if (id <= 0L) return false
        return try {
            val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED,
                    DownloadManager.STATUS_SUCCESSFUL -> true
                    else -> {
                        prefs.edit()
                            .remove("pending_download_id")
                            .remove("pending_apk_path")
                            .putLong("last_version_check_at", 0L)
                            .apply()
                        false
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }


    fun showDownloads(activity: Activity) {
        activeActivity = activity
        executor.execute {
            try {
                val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val manifest = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val item = JSONObject(manifest).getJSONObject(appKey)
                val latestCode = item.getLong("versionCode")
                val latestName = item.getString("versionName")
                val apkUrl = item.getString("apkUrl")
                val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
                val currentCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
                val currentName = packageInfo.versionName ?: "sconosciuta"
                main.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    val updateAvailable = latestCode > currentCode
                    val message = if (updateAvailable) {
                        "Versione installata: $currentName\nNuova versione disponibile: $latestName\n\nPuoi scaricare direttamente l'APK e aggiornare questa app. I dati e l'attivazione vengono mantenuti."
                    } else {
                        "Versione installata: $currentName\nUltima versione pubblicata: $latestName\n\nDAVIX è aggiornato. Puoi comunque scaricare nuovamente il pacchetto APK da qui."
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("Aggiornamenti DAVIX")
                        .setMessage(message)
                        .setNegativeButton("Chiudi", null)
                        .setPositiveButton(if (updateAvailable) "Scarica e aggiorna" else "Scarica APK") { _, _ ->
                            downloadUpdate(activity, latestName, apkUrl, latestCode)
                        }
                        .show()
                }
            } catch (_: Exception) {
                main.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    AlertDialog.Builder(activity)
                        .setTitle("Aggiornamenti DAVIX")
                        .setMessage("Non riesco a raggiungere il servizio degli aggiornamenti. Riprova quando la connessione è disponibile.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun downloadUpdate(activity: Activity, versionName: String, apkUrl: String, versionCode: Long) {
        if (downloadStartInProgress) return
        downloadStartInProgress = true
        executor.execute {
            try {
                val fileName = "DAVIX-$appKey-$versionName.apk"
                val request = DownloadManager.Request(Uri.parse(apkUrl))
                    .setTitle("Aggiornamento DAVIX")
                    .setDescription("Download versione $versionName")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, fileName)
                val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val id = manager.enqueue(request)
                val destination = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                prefs.edit()
                    .putLong("pending_download_id", id)
                    .putString("pending_apk_path", destination.absolutePath)
                    .putLong("pending_version_code", versionCode)
                    .putBoolean("installer_launched", false)
                    .apply()
                main.post {
                    downloadStartInProgress = false
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(activity, "Download aggiornamento avviato", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                main.post {
                    downloadStartInProgress = false
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(activity, "Impossibile avviare il download", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }



    private fun resumePendingInstall(activity: Activity) {
        if (prefs.getBoolean("installer_launched", false)) return
        val path = prefs.getString("pending_apk_path", null) ?: return
        val id = prefs.getLong("pending_download_id", -1L)

        executor.execute {
            val status = if (id > 0L) {
                runCatching {
                    val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
                    }
                }.getOrNull()
            } else {
                DownloadManager.STATUS_SUCCESSFUL
            }

            if (status != DownloadManager.STATUS_SUCCESSFUL) return@execute
            val file = File(path)
            if (!file.exists() || file.length() <= 0L) return@execute

            main.post {
                if (activeActivity !== activity || !activityResumed || activity.isFinishing || activity.isDestroyed) return@post
                val waitingForPermission = prefs.getBoolean("awaiting_install_permission", false)
                val canInstall = Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()
                if (waitingForPermission) {
                    prefs.edit().putBoolean("awaiting_install_permission", false).apply()
                    if (!canInstall) {
                        Toast.makeText(activity, "Per installare l'aggiornamento, consenti a DAVIX di installare app da questa origine e torna qui.", Toast.LENGTH_LONG).show()
                        return@post
                    }
                }
                installOrRequestPermission(activity, file)
            }
        }
    }



    private fun installOrRequestPermission(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            try {
                prefs.edit().putBoolean("awaiting_install_permission", true).apply()
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                Toast.makeText(activity, "Consenti a DAVIX di installare aggiornamenti. Al ritorno, l'installazione si aprirà automaticamente.", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                prefs.edit().putBoolean("awaiting_install_permission", false).apply()
                Toast.makeText(activity, "Abilita l'installazione da questa app nelle impostazioni Android", Toast.LENGTH_LONG).show()
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.davixupdater.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
            prefs.edit()
                .putBoolean("installer_launched", true)
                .putBoolean("download_completed", false)
                .apply()
        } catch (_: Exception) {
            Toast.makeText(activity, "Non è stato possibile aprire il programma di installazione Android", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val MANIFEST_URL = "https://github.com/davidealpegiani-82/sky-uno-extractor/releases/download/player-lite-latest/version.json"
    }
}
