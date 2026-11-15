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
    private val executor = Executors.newSingleThreadExecutor()
    private var activeActivity: Activity? = null
    private var activeDownloadId = -1L
    private var receiverRegistered = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != activeDownloadId) return
            val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(id)
            manager.query(query).use { cursor ->
                if (!cursor.moveToFirst()) return
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val path = prefs.getString("pending_apk_path", null)
                    if (path != null) main.post { activeActivity?.let { installOrRequestPermission(it, File(path)) } }
                } else {
                    main.post { activeActivity?.let { Toast.makeText(it, "Download aggiornamento non riuscito", Toast.LENGTH_LONG).show() } }
                }
            }
        }
    }

    fun check(activity: Activity) {
        activeActivity = activity
        resumePendingInstall(activity)
        if (prefs.getBoolean("checked_this_process", false)) return
        prefs.edit().putBoolean("checked_this_process", true).apply()
        if (!receiverRegistered) {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") app.registerReceiver(downloadReceiver, filter)
            receiverRegistered = true
        }
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
                val currentCode = if (Build.VERSION.SDK_INT >= 28) {
                    app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    app.packageManager.getPackageInfo(app.packageName, 0).versionCode.toLong()
                }
                if (latestCode > currentCode) {
                    main.post {
                        val current = activeActivity ?: return@post
                        if (current.isFinishing || current.isDestroyed) return@post
                        AlertDialog.Builder(current)
                            .setTitle("Aggiornamento DAVIX disponibile")
                            .setMessage("È disponibile la versione $latestName.\nVersione installata: ${app.packageManager.getPackageInfo(app.packageName, 0).versionName}.\n\nVuoi scaricarla e avviare l'installazione?")
                            .setNegativeButton("Più tardi", null)
                            .setPositiveButton("Aggiorna") { _, _ -> downloadUpdate(current, latestName, apkUrl) }
                            .show()
                    }
                }
            } catch (_: Exception) {
                // Il controllo aggiornamenti è non bloccante: DAVIX resta utilizzabile offline.
            }
        }
    }

    private fun downloadUpdate(activity: Activity, versionName: String, apkUrl: String) {
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
            activeDownloadId = id
            val destination = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            prefs.edit()
                .putLong("pending_download_id", id)
                .putString("pending_apk_path", destination.absolutePath)
                .apply()
            Toast.makeText(activity, "Download aggiornamento avviato", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(activity, "Impossibile avviare il download", Toast.LENGTH_LONG).show()
        }
    }

    private fun resumePendingInstall(activity: Activity) {
        val path = prefs.getString("pending_apk_path", null) ?: return
        val file = File(path)
        if (file.exists() && file.length() > 0L) installOrRequestPermission(activity, file)
    }

    private fun installOrRequestPermission(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                Toast.makeText(activity, "Consenti a DAVIX di installare aggiornamenti, poi torna all'app", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
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
        } catch (_: Exception) {
            Toast.makeText(activity, "Non è stato possibile aprire il programma di installazione Android", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val MANIFEST_URL = "https://github.com/davidealpegiani-82/sky-uno-extractor/releases/download/player-lite-latest/version.json"
    }
}
