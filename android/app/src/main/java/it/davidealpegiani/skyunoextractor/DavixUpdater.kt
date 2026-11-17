package it.davidealpegiani.skyunoextractor

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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
    @Volatile private var activeDownloadId = -1L
    private var receiverRegistered = false
    private var checkedThisProcess = false
    @Volatile private var activityResumed = false
    private var progressGeneration = 0
    private var downloadStartInProgress = false
    private var downloadDialog: AlertDialog? = null
    private var downloadProgress: ProgressBar? = null
    private var downloadStatus: TextView? = null
    private var progressRunnable: Runnable? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != prefs.getLong("pending_download_id", -1L)) return

            // DownloadManager è un servizio Binder: non interrogare il servizio dal receiver/main thread.
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
                    stopProgressPolling()
                    val current = activeActivity
                    if (!activityResumed || current == null || current.isFinishing || current.isDestroyed) return@post
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        showInstallReadyDialog(current)
                    } else {
                        dismissDownloadDialog()
                        AlertDialog.Builder(current).setTitle("Download non riuscito")
                            .setMessage("Non è stato possibile scaricare l'aggiornamento. Controlla la connessione e riprova.")
                            .setPositiveButton("OK", null).show()
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
        if (activeActivity === activity) {
            activityResumed = false
            stopProgressPolling()
        }
    }

    fun check(activity: Activity) {
        activeActivity = activity
        if (!receiverRegistered) {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") app.registerReceiver(downloadReceiver, filter)
            receiverRegistered = true
        }

        // Tutte le interrogazioni a DownloadManager e PackageManager restano su un thread di lavoro.
        executor.execute {
            try {
                val cleaned = clearCompletedInstallIfApplied()
                if (cleaned) {
                    main.post {
                        if (activeActivity === activity) dismissDownloadDialog()
                    }
                } else {
                    // Prima si verifica se l'APK già installato ha superato il download pendente.
                    resumePendingInstall(activity)
                }

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



    // Dopo un'installazione riuscita il processo riparte con il nuovo versionCode.
    // In quel momento si elimina lo stato del vecchio download, altrimenti il suo
    // STATUS_SUCCESSFUL verrebbe interpretato come download ancora pendente per sempre.
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

        // La versione installata è la fonte di verità; rimuove eventuali download residui.
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
                prefs.edit().putLong("pending_download_id", id)
                    .putString("pending_apk_path", destination.absolutePath)
                    .putLong("pending_version_code", versionCode)
                    .putBoolean("installer_launched", false)
                    .putBoolean("download_completed", false).apply()
                main.post {
                    downloadStartInProgress = false
                    if (!activity.isFinishing && !activity.isDestroyed) showDownloadProgress(activity, id, versionName)
                }
            } catch (_: Exception) {
                main.post {
                    downloadStartInProgress = false
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        AlertDialog.Builder(activity).setTitle("Download non avviato")
                            .setMessage("Non è stato possibile avviare il download dell'aggiornamento. Riprova.")
                            .setPositiveButton("OK", null).show()
                    }
                }
            }
        }
    }



    private fun showDownloadProgress(activity: Activity, downloadId: Long, versionName: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismissDownloadDialog()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 12), dp(activity, 24), dp(activity, 8))
        }
        downloadStatus = TextView(activity).apply {
            text = "Connessione al server…"
            textSize = 14f
        }
        downloadProgress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        layout.addView(downloadStatus)
        layout.addView(downloadProgress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 12)
        ).apply { topMargin = dp(activity, 16) })
        downloadDialog = AlertDialog.Builder(activity).setTitle("Download DAVIX $versionName")
            .setView(layout).setNegativeButton("Nascondi", null).setCancelable(false).create()
        downloadDialog?.show()
        startProgressPolling(downloadId)
    }

    private fun startProgressPolling(downloadId: Long) {
        stopProgressPolling()
        val generation = progressGeneration
        val task = object : Runnable {
            override fun run() {
                // La query Binder viene eseguita in background; il main thread aggiorna soltanto le View.
                executor.execute {
                    val snapshot: Triple<Int, Long, Long>? = runCatching {
                        val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                            if (!cursor.moveToFirst()) null else Triple(
                                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            )
                        }
                    }.getOrNull()

                    main.post {
                        if (generation != progressGeneration) return@post
                        if (snapshot == null) {
                            downloadStatus?.text = "Verifica dello stato del download…"
                            main.postDelayed(this, 1500L)
                            return@post
                        }

                        val (status, downloaded, total) = snapshot
                        when (status) {
                            DownloadManager.STATUS_PENDING -> {
                                downloadStatus?.text = "In attesa di avvio del download…"
                                downloadProgress?.isIndeterminate = true
                            }
                            DownloadManager.STATUS_RUNNING -> {
                                if (total > 0L) {
                                    val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                    downloadProgress?.isIndeterminate = false
                                    downloadProgress?.progress = percent
                                    downloadStatus?.text = "Scaricamento… " + percent + "%  ·  " + formatBytes(downloaded) + " / " + formatBytes(total)
                                } else {
                                    downloadProgress?.isIndeterminate = true
                                    downloadStatus?.text = "Scaricamento… " + formatBytes(downloaded)
                                }
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                downloadProgress?.isIndeterminate = true
                                downloadStatus?.text = "Download in pausa. Android riproverà automaticamente."
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                stopProgressPolling()
                                prefs.edit().putBoolean("download_completed", true).apply()
                                val current = activeActivity
                                if (activityResumed && current != null && !current.isFinishing && !current.isDestroyed) {
                                    showInstallReadyDialog(current)
                                }
                                return@post
                            }
                            DownloadManager.STATUS_FAILED -> {
                                stopProgressPolling()
                                dismissDownloadDialog()
                                val current = activeActivity
                                if (current != null && !current.isFinishing && !current.isDestroyed) {
                                    AlertDialog.Builder(current).setTitle("Download non riuscito")
                                        .setMessage("Il download dell'APK non è riuscito. Controlla la connessione e riprova.")
                                        .setPositiveButton("OK", null).show()
                                }
                                return@post
                            }
                        }
                        main.postDelayed(this, 1500L)
                    }
                }
            }
        }
        progressRunnable = task
        main.post(task)
    }



    private fun stopProgressPolling() {
        // Invalida anche una query già in corso: non deve riprogrammare il polling dopo la chiusura.
        progressGeneration += 1
        progressRunnable?.let { main.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun dismissDownloadDialog() {
        stopProgressPolling()
        downloadDialog?.dismiss()
        downloadDialog = null
        downloadProgress = null
        downloadStatus = null
    }

    private fun showInstallReadyDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed || prefs.getBoolean("installer_launched", false)) return
        dismissDownloadDialog()
        AlertDialog.Builder(activity).setTitle("Download completato")
            .setMessage("L'APK di DAVIX è stato scaricato correttamente. Vuoi procedere con l'aggiornamento?")
            .setNegativeButton("Più tardi", null)
            .setPositiveButton("Installa aggiornamento") { _, _ ->
                val path = prefs.getString("pending_apk_path", null) ?: return@setPositiveButton
                val file = File(path)
                if (file.exists() && file.length() > 0L) installOrRequestPermission(activity, file)
                else AlertDialog.Builder(activity).setMessage("Il file APK non è disponibile. Avvia nuovamente il download.")
                    .setPositiveButton("OK", null).show()
            }.show()
    }

    private fun resumePendingInstall(activity: Activity) {
        if (prefs.getBoolean("installer_launched", false)) return
        val id = prefs.getLong("pending_download_id", -1L)
        if (id <= 0L) return

        executor.execute {
            val status = runCatching {
                val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
                }
            }.getOrNull() ?: return@execute

            main.post {
                if (activeActivity !== activity || !activityResumed || activity.isFinishing || activity.isDestroyed) return@post
                when (status) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED ->
                        showDownloadProgress(activity, id, "in corso")
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        prefs.edit().putBoolean("download_completed", true).apply()
                        showInstallReadyDialog(activity)
                    }
                    else -> prefs.edit().remove("pending_download_id").remove("pending_apk_path")
                        .remove("pending_version_code").apply()
                }
            }
        }
    }



    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return bytes.toString() + " B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(java.util.Locale.ITALY, "%.0f KB", kb)
        return String.format(java.util.Locale.ITALY, "%.1f MB", kb / 1024.0)
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
        private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
        private const val MANIFEST_URL = "https://github.com/davidealpegiani-82/sky-uno-extractor/releases/download/player-lite-latest/version.json"
    }
}
