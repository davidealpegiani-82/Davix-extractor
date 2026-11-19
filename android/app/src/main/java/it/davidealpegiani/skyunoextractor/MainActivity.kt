package it.davidealpegiani.skyunoextractor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.AtomicFile
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteButton
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.MediaRouteButtonFactory
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors

private data class ChannelArchiveEntry(
    val name: String,
    val url: String,
    val logo: String,
    val selected: Boolean,
    val category: String,
    val tvgId: String,
    val tvgName: String
)

data class TvChannel(
    val name: String,
    val url: String,
    val logo: String = "",
    var selected: Boolean = true,
    var category: String = "Non classificati",
    var tvgId: String = "",
    var tvgName: String = ""
)

@OptIn(UnstableApi::class)
class MainActivity : FragmentActivity() {
    private val channels = mutableListOf<TvChannel>()
    // Un solo writer seriale: impedisce che snapshot più vecchi finiscano su disco dopo quelli recenti.
    private val archiveExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "DAVIX-Archive").apply { isDaemon = true }
    }
    private val categories = mutableListOf("Intrattenimento", "Sport", "Cinema", "Documentari", "Musica", "XXX", "Non classificati")
    private val categoryPrefs by lazy { getSharedPreferences("davix_extractor_categories", MODE_PRIVATE) }
    // Selezione multipla separata dalla selezione di esportazione.
    private var bulkSelectionMode = false
    private val bulkSelectedIndices = mutableSetOf<Int>()
    // Limita le View create contemporaneamente per ogni cartella: le playlist IPTV possono contenere migliaia di righe.
    private val folderPageSizes = mutableMapOf<String, Int>()
    // Le cartelle sono chiuse per impostazione predefinita: niente liste chilometriche.
    private val expandedCategoryPaths = mutableSetOf<String>()
    // null = conserva i group-title originali della playlist.
    private var pendingImportCategory: String? = null
    private lateinit var mainContainer: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var selectedList: LinearLayout
    private lateinit var savedPlaylistsList: LinearLayout
    private lateinit var otherList: LinearLayout
    private lateinit var selectedHeading: TextView
    private lateinit var otherHeading: TextView
    private lateinit var playerDock: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var status: TextView
    private lateinit var playbackInfo: TextView
    private lateinit var currentChannelLabel: TextView
    private lateinit var importProgressPanel: LinearLayout
    private lateinit var importProgressLabel: TextView
    private lateinit var importProgressBar: ProgressBar
    private var currentPlayingChannelUrl: String? = null
    private var importInProgress = false
    private lateinit var count: TextView
    private lateinit var playerView: PlayerView
    private lateinit var localPlayer: ExoPlayer
    private lateinit var player: Player
    private var castPlayer: CastPlayer? = null
    private var castInitWarning: String? = null
    private lateinit var scroll: ScrollView
    private var pendingHtml = ""
    private var fullscreen = false
    private val retryHandler = Handler(Looper.getMainLooper())
    private var playbackRetryCount = 0
    private var playbackRetryTask: Runnable? = null
    private var stablePlaybackTask: Runnable? = null
    // Invalida i tentativi appartenenti al canale precedente.
    private var playbackGeneration = 0

    // Diagnostica visibile: misura il buffer realmente disponibile durante i blocchi.
    private val playbackInfoTask = object : Runnable {
        override fun run() {
            if (!::player.isInitialized || !::playbackInfo.isInitialized || isFinishing || isDestroyed) return
            val state = when (player.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "?"
            }
            val aheadMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
            val liveOffsetMs = player.currentLiveOffset
            val liveText = if (liveOffsetMs == C.TIME_UNSET) "n/d" else "${liveOffsetMs / 1000}s"
            playbackInfo.text = "HLS: $state · buffer ${aheadMs / 1000}s · live $liveText · " +
                if (player.isLoading) "download attivo" else "download fermo"
            retryHandler.postDelayed(this, 1000L)
        }
    }

    companion object {
        private const val PICK_FILE = 4101
        private const val SAVE_HTML = 4102
        private const val CYAN = 0xFF39D7FF.toInt()
        private const val BG = 0xFF080B12.toInt()
        private const val PANEL = 0xFF121A29.toInt()
        private const val MUTED = 0xFF9EACC0.toInt()
        private const val CHANNELS_PER_FOLDER_PAGE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCategorySettings()
        restoreChannelLibrary()
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = 0
        // Profilo più leggero per dispositivi TV/Android con memoria limitata.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // buffer minimo
                60_000, // limite massimo: evita riserve eccessive
                5_000, // avvio iniziale più rapido
                10_000 // ripresa dopo esaurimento del buffer
            )
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        // Dopo ogni rebuffer Media3 aumenta il margine dal bordo live, così il
        // player smette di inseguire segmenti pubblicati in ritardo dal server.
        val liveSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(0.97f)
            .setFallbackMaxPlaybackSpeed(1.04f)
            .setTargetLiveOffsetIncrementOnRebufferMs(15_000)
            .build()

        // Usa uno User-Agent Android/browser invece di identificare le richieste
        // come DAVIX: alcuni provider accettano Wiseplay/browser e rifiutano UA custom.
        val defaultUserAgent = runCatching {
            android.webkit.WebSettings.getDefaultUserAgent(this)
        }.getOrDefault("Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome Safari/537.36")
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(defaultUserAgent)
            .setDefaultRequestProperties(mapOf("Accept" to "*/*"))
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(45_000)
            .setAllowCrossProtocolRedirects(true)

        // La factory standard riconosce HLS, DASH e flussi progressivi:
        // non forziamo più ogni URL a essere interpretato come HLS.
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))

        localPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(liveSpeedControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        // Diagnostica HLS: registra manifest/segmenti e problemi HTTP senza
        // esporre URL completi o token di accesso nei log.
        localPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadStarted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                val kind = when (mediaLoadData.dataType) {
                    C.DATA_TYPE_MANIFEST -> "manifest"
                    C.DATA_TYPE_MEDIA -> "segment"
                    else -> "type-" + mediaLoadData.dataType
                }
                Log.d(
                    "DAVIX-HLS",
                    "load start=" + kind + " host=" + (loadEventInfo.dataSpec.uri.host ?: "?")
                )
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                val kind = when (mediaLoadData.dataType) {
                    C.DATA_TYPE_MANIFEST -> "manifest"
                    C.DATA_TYPE_MEDIA -> "segment"
                    else -> "type-" + mediaLoadData.dataType
                }
                Log.d(
                    "DAVIX-HLS",
                    "load=" + kind + " host=" + (loadEventInfo.dataSpec.uri.host ?: "?") +
                        " bytes=" + loadEventInfo.bytesLoaded +
                        " durationMs=" + loadEventInfo.loadDurationMs
                )
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                Log.w(
                    "DAVIX-HLS",
                    "load error host=" + (loadEventInfo.dataSpec.uri.host ?: "?") +
                        " canceled=" + wasCanceled + " " +
                        error.javaClass.simpleName + ": " +
                        (error.message ?: "").replace(Regex("https?://\\S+"), "[indirizzo omesso]")
                )
            }
        })
        // Google Cast è opzionale: un problema del framework Cast non deve
        // impedire l'avvio dell'estrattore o la riproduzione locale.
        player = try {
            CastPlayer.Builder(this).setLocalPlayer(localPlayer).build().also { castPlayer = it }
        } catch (e: Exception) {
            castInitWarning = e.message ?: e.javaClass.simpleName
            localPlayer
        } catch (e: LinkageError) {
            castInitWarning = e.message ?: e.javaClass.simpleName
            localPlayer
        }
        player.playWhenReady = false
        buildInterface()
        castInitWarning?.let { status.text = "Google Cast non disponibile; riproduzione locale attiva." }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val detail = generateSequence(error as Throwable?) { it.cause }
                    .take(4)
                    .mapNotNull { it.message?.takeIf { message -> message.isNotBlank() } }
                    .map { it.replace(Regex("https?://\\S+"), "[indirizzo omesso]") }
                    .joinToString(" → ")
                Log.e(
                    "DAVIX-HLS",
                    "player error code=" + error.errorCodeName +
                        " positionMs=" + player.currentPosition +
                        " bufferedMs=" + player.totalBufferedDuration +
                        " liveOffsetMs=" + player.currentLiveOffset
                )
                val httpStatus = generateSequence(error as Throwable?) { it.cause }
                    .mapNotNull { (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode }
                    .firstOrNull()
                val retryable = if (httpStatus != null) {
                    httpStatus == 408 || httpStatus == 425 || httpStatus == 429 || httpStatus in 500..599
                } else {
                    error.errorCodeName.contains("IO_") ||
                        error.errorCodeName.contains("BEHIND_LIVE_WINDOW")
                }
                if (retryable) {
                    val delays = longArrayOf(2_000L, 4_000L, 8_000L, 12_000L, 15_000L, 30_000L)
                    val delay = delays[playbackRetryCount.coerceAtMost(delays.lastIndex)]
                    playbackRetryCount = (playbackRetryCount + 1).coerceAtMost(10_000)
                    val failedGeneration = playbackGeneration
                    status.text = "Connessione instabile. Nuovo tentativo tra " + (delay / 1000) + " s…"
                    playbackRetryTask?.let { retryHandler.removeCallbacks(it) }
                    val task = Runnable {
                        if (!isFinishing && !isDestroyed && ::player.isInitialized &&
                            failedGeneration == playbackGeneration
                        ) {
                            // Un seek è necessario solo se ExoPlayer segnala esplicitamente
                            // BEHIND_LIVE_WINDOW: mai durante un normale buffering.
                            if (error.errorCodeName.contains("BEHIND_LIVE_WINDOW")) {
                                Log.w("DAVIX-HLS", "live window persa: riallineamento al punto live predefinito")
                                player.seekToDefaultPosition()
                            }
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }
                    playbackRetryTask = task
                    retryHandler.postDelayed(task, delay)
                } else {
                    status.text = if (httpStatus != null) {
                        "HTTP $httpStatus: " + when (httpStatus) {
                            401 -> "autenticazione richiesta"
                            403 -> "accesso negato dal server"
                            404 -> "flusso o segmento non trovato"
                            410 -> "flusso non più disponibile"
                            429 -> "troppe richieste"
                            in 500..599 -> "server temporaneamente in errore"
                            else -> "risposta non valida"
                        } + ". Verifica la sorgente o seleziona un altro flusso."
                    } else {
                        "Errore ${error.errorCodeName} (${error.errorCode}): " +
                            detail.ifBlank { "flusso interrotto o non compatibile" }
                    }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) status.text = "Caricamento del flusso…"
                if (playbackState == Player.STATE_READY) status.text = "Video pronto."
                updateKeepScreenOn()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updateKeepScreenOn()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateKeepScreenOn()
                stablePlaybackTask?.let { retryHandler.removeCallbacks(it) }
                stablePlaybackTask = null
                if (isPlaying) {
                    val task = Runnable {
                        if (::player.isInitialized && player.isPlaying) playbackRetryCount = 0
                    }
                    stablePlaybackTask = task
                    retryHandler.postDelayed(task, 30_000L)
                }
            }
        })
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(source: Intent?) {
        val data = source?.data ?: return
        if (data.scheme != "skyunoextractor" || data.host != "play") return
        val streamUrl = data.getQueryParameter("url")?.takeIf {
            it.startsWith("https://", true) || it.startsWith("http://", true)
        } ?: run {
            status.text = "Collegamento non valido: indirizzo del flusso mancante."
            return
        }
        val channelName = data.getQueryParameter("name")?.takeIf { it.isNotBlank() } ?: "Canale selezionato"
        status.text = "Apertura da player web: $channelName"
        currentPlayingChannelUrl = streamUrl
        if (::currentChannelLabel.isInitialized) currentChannelLabel.text = "▶  IN RIPRODUZIONE · $channelName"
        playbackGeneration++
        try {
            playbackRetryTask?.let { retryHandler.removeCallbacks(it) }
            playbackRetryTask = null
            playbackRetryCount = 0
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(streamUrl)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(channelName).build())
            streamMimeType(streamUrl)?.let { mediaItemBuilder.setMimeType(it) }
            val mediaItem = mediaItemBuilder
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(60_000)
                        .setMinOffsetMs(45_000)
                        .setMaxOffsetMs(120_000)
                        .setMinPlaybackSpeed(0.97f)
                        .setMaxPlaybackSpeed(1.03f)
                        .build()
                )
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            status.text = "Impossibile avviare il canale: ${e.message}"
        }
    }

    // Mantiene acceso lo schermo durante la riproduzione locale, evitando
    // che il timeout di standby mandi l'Activity in onStop() e metta in pausa il video.
    // Durante Google Cast o quando la riproduzione è in pausa il comportamento resta normale.
    private fun updateKeepScreenOn() {
        val localPlayback = ::player.isInitialized &&
            player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED &&
            (castPlayer == null || player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL)
        if (localPlayback) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildInterface() {
        fullscreenContainer = FrameLayout(this)
        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(18))
            setBackgroundColor(BG)
        }
        scroll.addView(root)
        mainContainer.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Player dock: fixed at the bottom while the channel list scrolls.
        playerDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(8))
            setBackgroundColor(PANEL)
            elevation = dp(10).toFloat()
        }
        val dockHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dockTitle = TextView(this).apply {
            text = "▶  PLAYER"
            textSize = 12f
            setTextColor(CYAN)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        dockHeader.addView(dockTitle, LinearLayout.LayoutParams(0, dp(28), 1f))
        // Non creare la View Cast se il framework non è disponibile:
        // alcuni dispositivi/temi possono fallire già nel costruttore MediaRouteButton.
        if (castPlayer != null) {
            try {
                val castButton = MediaRouteButton(this).apply {
                    contentDescription = "Trasmetti a Google Cast"
                    setBackgroundColor(Color.TRANSPARENT)
                }
                dockHeader.addView(castButton, LinearLayout.LayoutParams(dp(48), dp(42)))
                MediaRouteButtonFactory.setUpMediaRouteButton(this, castButton)
            } catch (e: Exception) {
                castInitWarning = e.message ?: e.javaClass.simpleName
                castPlayer = null
            } catch (e: LinkageError) {
                castInitWarning = e.message ?: e.javaClass.simpleName
                castPlayer = null
            }
        }
        val fullButton = buttonView("⛶ Schermo intero") { toggleFullscreen() }
        dockHeader.addView(fullButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        playerDock.addView(dockHeader)
        currentChannelLabel = TextView(this).apply {
            text = "NESSUN CANALE IN RIPRODUZIONE"
            textSize = 12f
            setTextColor(CYAN)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(5), dp(4), dp(7))
            maxLines = 2
        }
        playerDock.addView(currentChannelLabel)
        playerView = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(176))
        }
        playerDock.addView(playerView)
        playbackInfo = TextView(this).apply {
            text = "HLS: in attesa del flusso"
            textSize = 10f
            setTextColor(MUTED)
            setPadding(dp(3), dp(3), dp(3), dp(2))
        }
        playerDock.addView(playbackInfo)
        mainContainer.addView(playerDock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(mainContainer)

        val title = TextView(this).apply {
            text = "DAVIX EXTRACTOR"
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title)
        label("Estrattore playlist e riproduttore HLS nativo", MUTED, 13f)

        section("1 · CARICA LA PLAYLIST")
        label("Importa da un file oppure collega direttamente la tua playlist IPTV e la guida XMLTV tramite URL.", MUTED)
        button("🔗  Configura URL M3U ed EPG", false) { showIptvSourceSettings() }
        button("🌐  Importa playlist direttamente da URL", true) { importPlaylistFromUrl() }
        button("📂  Seleziona file", false) { showImportDestinationDialog() }
        status = label("Nessun file caricato.", MUTED)
        importProgressPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(PANEL)
            visibility = View.GONE
        }
        importProgressLabel = TextView(this).apply {
            text = "Preparazione importazione…"
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        importProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(CYAN)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF34445A.toInt())
        }
        importProgressPanel.addView(importProgressLabel)
        importProgressPanel.addView(importProgressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(7) })
        root.addView(importProgressPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        section("2 · PLAYLIST SALVATE")
        label("Le copie salvate restano sul dispositivo anche dopo la chiusura dell'app, finché non le elimini.", MUTED, 12f)
        savedPlaylistsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(savedPlaylistsList)
        button("🗑  Svuota archivio e lista canali", false) { confirmClearSavedPlaylists() }
        renderSavedPlaylists()

        section("3 · CANALI E SELEZIONE")
        count = label("0 canali importati", CYAN)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(buttonView("Seleziona tutti") { channels.forEach { it.selected = true }; renderChannels() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        row.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(buttonView("Deseleziona") { channels.forEach { it.selected = false }; renderChannels() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(row)
        button("▦  Gestisci categorie e sottocartelle", false) { showCategoryManager() }
        label("Usa il formato Sezione > Sottosezione, ad esempio Intrattenimento > Cinema.", MUTED, 12f)
        val bulkActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bulkActions.addView(buttonView("☑  Selezione multipla") {
            bulkSelectionMode = !bulkSelectionMode
            if (!bulkSelectionMode) bulkSelectedIndices.clear()
            renderChannels()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        bulkActions.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
        bulkActions.addView(buttonView("Sposta selezionati") { moveBulkSelectedChannels() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(bulkActions)

        selectedHeading = TextView(this).apply {
            text = "✓  SELEZIONATI PER ESPORTAZIONE"
            textSize = 12f
            setTextColor(CYAN)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(6))
        }
        root.addView(selectedHeading)
        selectedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(selectedList)
        otherHeading = TextView(this).apply {
            text = "ALTRI CANALI"
            textSize = 12f
            setTextColor(MUTED)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
        }
        root.addView(otherHeading)
        otherList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(otherList)
        button("⬇  Esporta player HTML", true) { requestHtmlExport() }
        label("Suggerimento: tocca il nome di un canale per riprodurlo; usa la casella per includerlo o escluderlo dall'esportazione.", MUTED, 12f)

        section("4 · DAVIX CLOUD SYNC")
        label("Pubblica la configurazione selezionata per renderla disponibile ai soli Player che autorizzi. Il servizio trasferisce la lista, mai il video.", MUTED, 12f)
        button("⚙  Configura DAVIX Cloud Sync", false) { showCloudSyncSettings() }
        button("☁  Pubblica aggiornamento remoto", true) { publishRemoteConfiguration() }
        val adminRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        adminRow.addView(buttonView("Genera codice") { approveCloudPlayer() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        adminRow.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
        adminRow.addView(buttonView("Gestisci dispositivi") { showCloudDevices() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(adminRow)
        button("⬇  Aggiornamenti DAVIX", false) {
            (application as DavixUpdaterApplication).showDownloads(this@MainActivity)
        }
        // Mostra subito l'archivio ripristinato, senza richiedere una nuova importazione.
        renderChannels()
    }

    private fun updateImportProgress(message: String, percent: Int? = null) {
        val update = Runnable {
            if (!::importProgressPanel.isInitialized || isFinishing || isDestroyed) return@Runnable
            importProgressPanel.visibility = View.VISIBLE
            importProgressLabel.text = message
            importProgressBar.isIndeterminate = percent == null
            if (percent != null) importProgressBar.progress = percent.coerceIn(0, 100)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update.run() else runOnUiThread(update)
    }

    private fun finishImportProgress() {
        if (!::importProgressPanel.isInitialized) return
        importProgressPanel.visibility = View.GONE
        importProgressBar.isIndeterminate = false
        importProgressBar.progress = 0
    }

    private fun showIptvSourceSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val playlistInput = EditText(this).apply {
            hint = "http://server/get.php?...type=m3u_plus"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            setText(IptvSourceSettings.playlistUrl(this@MainActivity))
        }
        val epgInput = EditText(this).apply {
            hint = "http://server/xmltv.php?username=...&password=..."
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            setText(IptvSourceSettings.epgUrl(this@MainActivity))
        }
        content.addView(labelForDialog("URL PLAYLIST M3U · credenziali nascoste"))
        content.addView(playlistInput)
        content.addView(labelForDialog("URL GUIDA XMLTV · credenziali nascoste"))
        content.addView(epgInput)
        content.addView(labelForDialog("Gli indirizzi vengono cifrati sul dispositivo. La sorgente XMLTV viene trasferita al Player solo tramite Cloud Sync HTTPS e conservata cifrata anche sul server e sul Player."))
        AlertDialog.Builder(this)
            .setTitle("Sorgenti IPTV / EPG")
            .setView(content)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva in modo protetto", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        try {
                            IptvSourceSettings.save(
                                this,
                                playlistInput.text.toString(),
                                epgInput.text.toString()
                            )
                            dialog.dismiss()
                            toast("URL M3U ed XMLTV salvati in modo protetto.")
                        } catch (e: Exception) {
                            toast(e.message ?: "Indirizzo non valido.")
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun importPlaylistFromUrl() {
        if (importInProgress) { toast("È già in corso un'importazione."); return }
        val address = IptvSourceSettings.playlistUrl(this)
        if (address.isBlank()) {
            toast("Configura prima l'URL della playlist M3U.")
            showIptvSourceSettings()
            return
        }
        importInProgress = true
        status.text = "Download playlist IPTV in corso…"
        updateImportProgress("Connessione alla sorgente IPTV…", null)
        Thread {
            try {
                val connection = (URL(address).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "audio/x-mpegurl,application/vnd.apple.mpegurl,text/plain,*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "DAVIX-Extractor/1.0")
                }
                val bytes = try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw IOException("Il server IPTV ha risposto HTTP $code.")
                    val expectedBytes = connection.getHeaderFieldLong("Content-Length", -1L)
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 64L * 1024L * 1024L) throw IOException("La playlist supera il limite di 64 MiB.")
                            output.write(buffer, 0, count)
                            val percent = if (expectedBytes > 0L) ((total * 30L / expectedBytes).toInt()).coerceIn(1, 30) else null
                            updateImportProgress("Download playlist · ${total / (1024 * 1024)} MB${if (expectedBytes > 0L) " / ${expectedBytes / (1024 * 1024)} MB" else " ricevuti"}", percent)
                        }
                    }
                    output.toByteArray()
                } finally {
                    connection.disconnect()
                }
                updateImportProgress("Download completato · analisi dei canali…", 32)
                val text = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
                val found = parseChannels(text) { parsed -> updateImportProgress("Analisi playlist · $parsed%", 32 + (parsed * 0.48).toInt()) }
                if (found.isEmpty()) throw IllegalStateException("Il collegamento non ha restituito una playlist M3U valida.")
                updateImportProgress("Analisi completata · classificazione e controllo duplicati…", 85)
                savePlaylistCopy("playlist-remota.m3u", bytes)
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        importInProgress = false
                        return@runOnUiThread
                    }
                    val existingChannelsByKey = channels.associateBy {
                        it.name.lowercase(Locale.ROOT) + "|" + it.url
                    }
                    val knownChannelKeys = existingChannelsByKey.keys.toHashSet()
                    val knownCategoryPaths = categories.toHashSet()
                    val savedCategoryMapping = runCatching {
                        JSONObject(categoryPrefs.getString("channel_categories_json", "{}") ?: "{}")
                    }.getOrDefault(JSONObject())
                    var added = 0
                    var duplicates = 0
                    var reorganized = 0
                    var processed = 0
                    val batchSize = 200
                    val importHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    val importBatch = object : Runnable {
                        override fun run() {
                            if (isFinishing || isDestroyed) {
                                importInProgress = false
                                return
                            }
                            var inBatch = 0
                            while (processed < found.size && inBatch < batchSize) {
                                val channel = found[processed]
                                // La categoria salvata può essere una scelta manuale oppure una vecchia
                                // classificazione automatica. Classifichiamo sempre usando il group-title
                                // originale della playlist, non la categoria memorizzata in precedenza.
                                val sourceGroup = channel.category
                                val savedCategory = normalizeCategoryPath(savedCategoryMapping.optString(channel.url))
                                val inferred = inferUrlCategoryPath(channel.copy(category = sourceGroup))
                                // Le vecchie importazioni URL salvavano il group-title del provider
                                // come categoria semplice. Se il valore salvato coincide ancora con il
                                // group-title originale, non è una personalizzazione: va migrato nella
                                // gerarchia LIVE/VOD. Una categoria manuale diversa dal group-title resta intatta.
                                val savedPath = normalizeCategoryPath(savedCategory)
                                val sourcePath = normalizeCategoryPath(sourceGroup)
                                val isLegacyProviderGroup = savedPath.isNotBlank() &&
                                    savedPath.equals(sourcePath, ignoreCase = true) &&
                                    !savedPath.contains(" > ")
                                val preserveManualCategory = savedCategory.isNotBlank() &&
                                    !isAutomaticallyClassifiedUrlCategory(savedCategory)
                                channel.category = if (preserveManualCategory && !isLegacyProviderGroup) {
                                    savedCategory
                                } else {
                                    inferred
                                }
                                if (knownCategoryPaths.add(channel.category)) categories.add(channel.category)
                                val key = channel.name.lowercase(Locale.ROOT) + "|" + channel.url
                                if (!knownChannelKeys.add(key)) {
                                    duplicates++
                                    val existing = existingChannelsByKey[key]
                                    val oldPath = normalizeCategoryPath(existing?.category.orEmpty())
                                    val wasAutomaticallyClassified = isAutomaticallyClassifiedUrlCategory(oldPath)
                                    if (existing != null && wasAutomaticallyClassified && existing.category != channel.category) {
                                        existing.category = channel.category
                                        reorganized++
                                    }
                                } else {
                                    channel.selected = !(channel.name.contains("sky", true) && (channel.name.contains("plus", true) || channel.name.contains("+1")))
                                    channels.add(channel)
                                    added++
                                }
                                processed++
                                inBatch++
                            }
                            if (processed < found.size) {
                                updateImportProgress("Inserimento canali · $processed / ${found.size}", 85 + (processed * 14 / found.size.coerceAtLeast(1)))
                                importHandler.postDelayed(this, 1L)
                            } else {
                                // Rimuove soltanto le cartelle automatiche ormai vuote:
                                // le categorie manuali e i relativi contenuti restano intatti.
                                categories.removeAll { category ->
                                    isAutomaticallyClassifiedUrlCategory(category) &&
                                        channels.none { it.category == category }
                                }
                                renderChannels()
                                saveCategorySettings()
                                persistChannelLibrary()
                                renderSavedPlaylists()
                                importInProgress = false
                                finishImportProgress()
                                status.text = "Playlist URL analizzata · ${found.size} canali letti · $added nuovi · $reorganized riclassificati · $duplicates già presenti."
                            }
                        }
                    }
                    importHandler.post(importBatch)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    importInProgress = false
                    finishImportProgress()
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Download M3U non riuscito: " + (e.message ?: "errore di rete")
                        toast(status.text.toString())
                    }
                }
            }
        }.start()
    }

    private fun showCloudSyncSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val endpoint = EditText(this).apply {
            hint = "https://nome-account.workers.dev"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(CloudSyncAdmin.baseUrl(this@MainActivity))
        }
        val adminKey = EditText(this).apply {
            hint = "Chiave amministratore (almeno 32 caratteri)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(labelForDialog("Indirizzo del servizio HTTPS"))
        content.addView(endpoint)
        content.addView(labelForDialog("Chiave amministratore · viene salvata cifrata nel dispositivo"))
        content.addView(adminKey)
        AlertDialog.Builder(this)
            .setTitle("Configurazione DAVIX Cloud Sync")
            .setView(content)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        try {
                            CloudSyncAdmin.saveSettings(
                                this,
                                endpoint.text.toString(),
                                adminKey.text.toString().ifBlank { null }
                            )
                            dialog.dismiss()
                            toast("Configurazione Cloud Sync salvata in modo protetto.")
                        } catch (e: Exception) {
                            toast(e.message ?: "Configurazione non valida.")
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun labelForDialog(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(MUTED)
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun cloudSyncErrorMessage(error: Exception): String = when (error) {
        is java.net.SocketTimeoutException ->
            "Timeout: il Worker DAVIX Cloud Sync non ha risposto in tempo. Verifica la connessione e riprova."
        is IOException ->
            "Worker DAVIX Cloud Sync non raggiungibile. Controlla rete, DNS e indirizzo HTTPS del servizio."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Errore imprevisto di DAVIX Cloud Sync."
    }

    private fun runCloudAdminTask(task: () -> JSONObject, onSuccess: (JSONObject) -> Unit) {
        status.text = "Connessione a DAVIX Cloud Sync…"
        Thread {
            try {
                val result = task()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) onSuccess(result)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Cloud Sync: ${cloudSyncErrorMessage(e)}"
                        toast(status.text.toString())
                    }
                }
            }
        }.start()
    }

    private fun publishRemoteConfiguration() {
        val chosen = selectedChannels()
        if (chosen.isEmpty()) {
            toast("Seleziona almeno un canale da pubblicare.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pubblicare ${chosen.size} canali?")
            .setMessage("La nuova configurazione diventerà disponibile ai Player autorizzati al loro prossimo avvio. La lista attualmente salvata sui Player resterà disponibile in caso di errore.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Pubblica") { _, _ ->
                val payload = JSONArray()
                chosen.forEachIndexed { index, channel ->
                    payload.put(
                        JSONObject()
                            .put("id", "channel-$index")
                            .put("name", channel.name)
                            .put("url", channel.url)
                            .put("logo", channel.logo)
                            .put("category", channel.category)
                            .put("tvgId", channel.tvgId)
                            .put("tvgName", channel.tvgName)
                    )
                }
                runCloudAdminTask(
                    task = {
                        CloudSyncAdmin.publish(
                            this,
                            payload,
                            JSONArray(categories),
                            IptvSourceSettings.epgUrl(this).takeIf { it.isNotBlank() }
                        )
                    },
                    onSuccess = { result ->
                        status.text = "Pubblicazione completata · versione ${result.optInt("version")} · ${result.optInt("channelCount")} canali."
                        toast(status.text.toString())
                    }
                )
            }
            .show()
    }

    private fun approveCloudPlayer() {
        runCloudAdminTask(
            task = { CloudSyncAdmin.createActivationCode(this) },
            onSuccess = { result ->
                val code = result.optString("code")
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(12), dp(24), dp(8))
                    addView(TextView(this@MainActivity).apply {
                        text = code
                        textSize = 30f
                        letterSpacing = 0.16f
                        setTextColor(CYAN)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        gravity = Gravity.CENTER
                    })
                    addView(labelForDialog("Codice monouso · valido 10 minuti · utilizzabile su un solo dispositivo"))
                }
                AlertDialog.Builder(this)
                    .setTitle("Nuovo codice di attivazione")
                    .setView(content)
                    .setPositiveButton("Copia codice") { _, _ ->
                        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Codice DAVIX Player", code))
                        toast("Codice copiato. Comunicalo al dispositivo da attivare.")
                    }
                    .setNegativeButton("Chiudi", null)
                    .show()
            }
        )
    }

    private fun showCloudDevices() {
        status.text = "Caricamento dispositivi…"
        Thread {
            try {
                val result = CloudSyncAdmin.devices(this)
                val authorized = result.optJSONArray("authorizedDevices") ?: JSONArray()
                val revoked = result.optJSONArray("revokedDevices") ?: JSONArray()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val panel = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(8), dp(20), dp(4))
                    }
                    val activeButton = Button(this).apply {
                        text = "AUTORIZZATI  ·  ${authorized.length()}"
                        isAllCaps = false
                        setOnClickListener { showDeviceGroup("Dispositivi autorizzati", authorized, true) }
                    }
                    val revokedButton = Button(this).apply {
                        text = "REVOCATI  ·  ${revoked.length()}"
                        isAllCaps = false
                        setOnClickListener { showDeviceGroup("Dispositivi revocati", revoked, false) }
                    }
                    panel.addView(activeButton)
                    panel.addView(revokedButton)
                    AlertDialog.Builder(this)
                        .setTitle("Gestione dispositivi")
                        .setView(panel)
                        .setNegativeButton("Chiudi", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread { if (!isFinishing && !isDestroyed) toast(cloudSyncErrorMessage(e)) }
            }
        }.start()
    }

    private fun showDeviceGroup(title: String, devices: JSONArray, canRevoke: Boolean) {
        if (devices.length() == 0) {
            toast("Nessun dispositivo in questo elenco.")
            return
        }
        val labels = Array(devices.length()) { index ->
            val item = devices.getJSONObject(index)
            val name = item.optString("displayName").ifBlank { item.optString("deviceName") }
            val lastSeen = item.optLong("lastSeenAt", 0L).let {
                if (it > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) else "mai collegato"
            }
            "$name · ${item.optString("platform")}\nUltimo accesso: $lastSeen"
        }
        AlertDialog.Builder(this)
            .setTitle("$title  ·  ${devices.length()}")
            .setItems(labels) { _, index ->
                val item = devices.getJSONObject(index)
                val deviceId = item.optString("deviceId")
                val currentName = item.optString("displayName").ifBlank { item.optString("deviceName") }
                val nameInput = EditText(this).apply {
                    setText(currentName)
                    setSingleLine(true)
                    hint = "Nome dispositivo (max 60 caratteri)"
                    filters = arrayOf(android.text.InputFilter.LengthFilter(60))
                }
                val actions = AlertDialog.Builder(this)
                    .setTitle(currentName)
                    .setMessage("Piattaforma: ${item.optString("platform")}\nStato: ${item.optString("status")}")
                    .setView(nameInput)
                    .setNeutralButton("Rinomina", null)
                if (canRevoke) actions.setPositiveButton("Revoca accesso") { _, _ ->
                    runCloudAdminTask(
                        task = { CloudSyncAdmin.revoke(this, deviceId) },
                        onSuccess = { toast("Dispositivo spostato nell'elenco revocati."); showCloudDevices() }
                    )
                } else actions.setPositiveButton("Chiudi", null)
                actions.setNegativeButton("Annulla", null)
                actions.create().also { dialog ->
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            val newName = nameInput.text.toString().trim()
                            if (newName.isBlank()) {
                                nameInput.error = "Inserisci un nome riconoscibile"
                            } else {
                                runCloudAdminTask(
                                    task = { CloudSyncAdmin.rename(this, deviceId, newName) },
                                    onSuccess = { toast("Nome dispositivo aggiornato."); showCloudDevices(); dialog.dismiss() }
                                )
                            }
                        }
                    }
                    dialog.show()
                }
            }
            .setNegativeButton("Indietro", null)
            .show()
    }

    private fun section(text: String) {
        val v = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(CYAN)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(8))
        }
        root.addView(v)
    }

    private fun label(text: String, color: Int = Color.WHITE, size: Float = 14f): TextView {
        val v = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(0, dp(5), 0, dp(5))
        }
        root.addView(v)
        return v
    }

    private fun space(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }

    private fun buttonView(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF26364D.toInt()))
            setOnClickListener { action() }
        }

    private fun button(text: String, primary: Boolean, action: () -> Unit) {
        val b = buttonView(text, action).apply {
            textSize = 14f
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(if (primary) 0xFF176D98.toInt() else 0xFF26364D.toInt()))
        }
        root.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(8)
        })
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "text/plain", "application/json", "audio/x-mpegurl", "application/vnd.apple.mpegurl"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, PICK_FILE)
    }

    @Deprecated("Legacy result API retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == PICK_FILE) pendingImportCategory = null
            return
        }
        when (requestCode) {
            PICK_FILE -> {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
                data.data?.let { if (uris.isEmpty()) uris.add(it) }
                importUris(uris)
            }
            SAVE_HTML -> {
                data.data?.let { uri ->
                    try {
                        contentResolver.openOutputStream(uri)?.use { it.write(pendingHtml.toByteArray(StandardCharsets.UTF_8)) }
                        toast("Player HTML esportato.")
                    } catch (e: Exception) { toast("Salvataggio non riuscito: ${e.message}") }
                }
            }
        }
    }

    private fun showImportDestinationDialog() {
        // Dialog personalizzato: setMessage() + setItems() sui dialog standard
        // può comprimere/nascondere l'elenco su alcuni temi e dispositivi Android.
        // La lista viene costruita esplicitamente e resta sempre scorrevole.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        val description = TextView(this).apply {
            text = "Scegli la sezione in cui inserire i canali. Puoi conservare i gruppi originali della playlist oppure assegnare tutti i canali a una destinazione."
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(10))
        }
        content.addView(description)

        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scrollChoices = ScrollView(this).apply {
            isFillViewport = true
            addView(choices)
        }
        content.addView(scrollChoices, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(320)
        ))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Destinazione dell'importazione")
            .setView(content)
            .setNegativeButton("Annulla", null)
            .create()

        fun addDestination(label: String, detail: String? = null, action: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(PANEL)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            val title = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            item.addView(title)
            if (!detail.isNullOrBlank()) {
                item.addView(TextView(this).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(MUTED)
                })
            }
            choices.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) })
        }

        addDestination("Classifica automaticamente per tipologia", "Usa nome canale e group-title per creare cartelle e sottocartelle tematiche.") {
            pendingImportCategory = null
            openPicker()
        }

        val available = categories
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        if (available.isEmpty()) {
            addDestination("Nessuna sezione disponibile", "Crea una nuova sezione per continuare.") { promptNewCategoryForImport() }
        } else {
            available.forEach { category ->
                val depth = category.split(">").size - 1
                val shown = if (depth > 0) ("   ".repeat(depth)) + "↳ " + category.substringAfterLast(">") .trim() else category
                addDestination(shown, if (depth > 0) "Sottocartella · $category" else "Sezione") {
                    pendingImportCategory = category
                    openPicker()
                }
            }
        }

        addDestination("＋  Crea nuova sezione o sottocartella", "Esempio: Intrattenimento > Cinema") {
            promptNewCategoryForImport()
        }
        dialog.show()
    }

    private fun promptNewCategoryForImport() {
        val input = EditText(this).apply {
            hint = "Intrattenimento > Cinema"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
        }
        AlertDialog.Builder(this)
            .setTitle("Nuova sezione / sottocartella")
            .setMessage("Per creare una sottocartella usa il formato Sezione > Sottosezione.")
            .setView(input)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Continua") { _, _ ->
                val path = normalizeCategoryPath(input.text.toString())
                if (path.isBlank()) { toast("Inserisci un nome valido."); return@setPositiveButton }
                if (categories.none { it.equals(path, true) }) categories.add(path)
                pendingImportCategory = path
                openPicker()
            }.show()
    }


    /**
     * Trasforma group-title e nome canale in un percorso gerarchico coerente.
     * Le regole sono volutamente conservative: ciò che non è riconoscibile
     * finisce in "Da classificare", senza essere assegnato a una categoria casuale.
     */
    /**
     * Classificazione dedicata alle playlist remote: prima identifica il tipo
     * di contenuto (LIVE / VOD-FILM / VOD-SERIE), poi assegna una categoria
     * semantica compatta. Il group-title è un indizio, non viene copiato alla
     * cieca come cartella: spesso i provider generano decine di varianti.
     */
    /**
     * Categorie automatiche DAVIX per le playlist URL.
     *
     * Primo livello: LIVE oppure VOD.
     * Secondo livello VOD: FILM oppure SERIE.
     * Le categorie di genere sono volutamente accorpate: non creiamo cartelle
     * per nazionalità, singoli sottogeneri o etichette commerciali del provider.
     */
    private fun inferUrlCategoryPath(channel: TvChannel): String {
        val group = channel.category.lowercase(Locale.ROOT)
            .replace('_', ' ').replace('-', ' ').replace('|', ' ')
            .replace(Regex("\\s+"), " ").trim()
        val name = channel.name.lowercase(Locale.ROOT)
            .replace('_', ' ').replace('-', ' ').replace('.', ' ')
            .replace(Regex("\\s+"), " ").trim()
        val url = channel.url.lowercase(Locale.ROOT)
        val path = runCatching { Uri.parse(channel.url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val source = "$group $name"
        fun hasIn(value: String, vararg terms: String) = terms.any { value.contains(it) }
        fun has(vararg terms: String) = hasIn(source, *terms)
        fun tokenIn(value: String, vararg terms: String) = terms.any {
            Regex("(^|[^a-z0-9])" + Regex.escape(it) + "([^a-z0-9]|$)").containsMatchIn(value)
        }
        fun token(vararg terms: String) = tokenIn(source, *terms)

        // Classificazione a evidenza graduata: gli indizi episodici/serie e i
        // percorsi VOD prevalgono sempre sulle etichette generiche "live".
        val episodePattern = Regex(
            "\\b(?:s[0-9]{1,2}\\s*e[0-9]{1,3}|[0-9]{1,2}x[0-9]{1,3}|season\\s*[0-9]{1,2}\\s*episode\\s*[0-9]{1,3}|stagione\\s*[0-9]{1,2}\\s*episodio\\s*[0-9]{1,3})\\b",
            RegexOption.IGNORE_CASE
        )
        val episodeEvidence = episodePattern.containsMatchIn(name) ||
            episodePattern.containsMatchIn(url) ||
            has("episodio", "episodi", "episode", "episodes", "stagione", "season", "serie tv", "tv series", "tv show", "series")
        val seriesPath = path.contains("/series/") || path.contains("/serie/")
        val moviePath = path.contains("/movie/") || path.contains("/movies/") || path.contains("/film/")
        val vodPath = path.contains("/vod/") || seriesPath || moviePath
        val vodGroup = has("serie", "series", "tv show", "tvshows", "film", "films", "movie", "movies", "video on demand", "on demand") ||
            token("vod")
        val vodExtension = Regex("\\.(mp4|mkv|avi|mov|m4v|wmv|webm)(?:$|[?&])").containsMatchIn(url)
        val seriesEvidence = seriesPath || episodeEvidence ||
            has("serie tv", "serie complete", "stagioni complete", "tv series", "tv show", "tv shows", "series")
        val movieEvidence = moviePath || has("film", "films", "movie", "movies")
        val strongVod = vodPath || seriesEvidence || movieEvidence ||
            has("video on demand", "on demand") || token("vod") || vodExtension
        val explicitLiveLabel = path.contains("/live/") || has("live", "canali tv", "tv channels", "linear", "broadcast")
        val liveStreamExtension = Regex("\\.(ts|m3u8)(?:$|[?&])").containsMatchIn(url)

        // Anche se il provider mette un VOD sotto una cartella chiamata live,
        // la presenza di prove episodiche o di un percorso VOD deve prevalere.
        val isVod = strongVod || (liveStreamExtension && (seriesEvidence || movieEvidence))
        if (isVod) {
            val isSeries = seriesEvidence && !moviePath
            val genre = if (isSeries) {
                when {
                    has("crime", "poliziesco", "investig", "detective", "gangster", "thriller") -> "Crime e Thriller"
                    has("commedia", "comedy", "sitcom", "comico") -> "Commedia"
                    has("horror", "terror") -> "Horror"
                    has("fantascienza", "sci fi", "sci-fi", "science fiction", "fantasy") -> "Fantascienza e Fantasy"
                    has("azione", "action", "adventure", "avventura") -> "Azione e Avventura"
                    has("animation", "animazione", "anime", "cartoon", "cartoni") -> "Anime e Animazione"
                    has("documentary", "documentario", "documentari", "docu", "reality", "talent") -> "Documentari e Reality"
                    has("drama", "drammatico", "dramatic", "fiction", "romance", "romantico") -> "Drammatiche"
                    else -> "Altre serie"
                }
            } else {
                when {
                    has("crime", "poliziesco", "investig", "detective", "gangster", "thriller", "suspense", "mystery", "mistero") -> "Crime e Thriller"
                    has("commedia", "comedy", "comico") -> "Commedia"
                    has("horror", "terror") -> "Horror"
                    has("fantascienza", "sci fi", "sci-fi", "science fiction", "fantasy") -> "Fantascienza e Fantasy"
                    has("azione", "action", "adventure", "avventura", "martial arts") -> "Azione e Avventura"
                    has("animation", "animazione", "anime", "cartoon", "cartoni", "family", "famiglia", "kids", "bambini") -> "Animazione e Famiglia"
                    has("documentary", "documentario", "documentari", "docu", "music", "musical", "concert") -> "Documentari e Musical"
                    has("drama", "drammatico", "dramatic", "fiction", "romance", "romantico", "war", "guerra", "history", "storico", "western") -> "Drammatici e Storici"
                    else -> "Altri film"
                }
            }
            return if (isSeries) "VOD > SERIE > $genre" else "VOD > FILM > $genre"
        }

        // Un'etichetta live è considerata valida solo in assenza di segnali VOD.
        // L'estensione .m3u8/.ts da sola non basta: alcuni provider la usano per VOD.
        val explicitLive = (explicitLiveLabel || liveStreamExtension) && !strongVod
        val liveCategory = when {
            has("xxx", "adult", "adulti", "erotic") -> "Adult"
            has("calcio", "football", "soccer", "serie a", "serie b", "champions league", "europa league", "conference league", "premier league", "bundesliga", "ligue 1", "coppa italia") -> "Sport > Calcio"
            has("sport", "dazn", "eurosport", "sky sport", "sportitalia", "formula 1", "formula1", "motogp", "moto gp", "superbike", "wrc", "rally", "nascar", "motori", "tennis", "atp", "wta", "basket", "basketball", "nba", "euroleague", "golf", "rugby", "volley", "pallavolo", "ciclismo", "olimpiadi") -> "Sport > Altri sport"
            has("news", "notizie", "rai news", "sky tg", "tgcom", "bbc", "cnn", "al jazeera", "france 24", "euronews") -> "News"
            has("documentar", "discovery", "national geographic", "nat geo", "history channel", "wildlife", "natura", "focus") -> "Documentari"
            has("cartoon", "cartoni", "boing", "k2", "nickelodeon", "cartoonito", "disney junior", "kids", "bambini") -> "Bambini"
            has("music", "musica", "mtv", "concert", "radio") -> "Musica"
            has("regional", "regionale", "local tv", "telelombardia", "telecity", "telenova") -> "Regionali"
            has("rai 1", "rai 2", "rai 3", "canale 5", "italia 1", "rete 4", "la7", "nove", "tv8", "real time", "dmax", "general", "entertainment", "intrattenimento", "reality", "masterchef", "x factor") -> "Intrattenimento"
            has("cinema", "sky cinema", "paramount", "film channel", "movie channel") -> "Cinema"
            has("international", "internazional", "world", "estero", "foreign") -> "Internazionali"
            else -> "Altri canali"
        }
        return "LIVE > $liveCategory"
    }
    /**
     * Riconosce le sole cartelle generate automaticamente da DAVIX.
     * Le categorie personalizzate, anche se create sotto LIVE o VOD,
     * non vengono sovrascritte durante una nuova importazione.
     */
    private fun isAutomaticallyClassifiedUrlCategory(raw: String): Boolean {
        val path = normalizeCategoryPath(raw)
        val generated = setOf(
            "LIVE > Adult", "LIVE > Sport > Calcio", "LIVE > Sport > Altri sport",
            "LIVE > News", "LIVE > Documentari", "LIVE > Bambini", "LIVE > Musica",
            "LIVE > Regionali", "LIVE > Intrattenimento", "LIVE > Cinema",
            "LIVE > Internazionali", "LIVE > Altri canali",
            // Percorsi LIVE generati dalle versioni precedenti.
            "LIVE > Sport > Motori", "LIVE > Sport > Tennis", "LIVE > Sport > Basket",
            // Generi VOD della precedente classificazione, da ricondurre al nuovo schema.
            "VOD > FILM > Azione e Avventura", "VOD > FILM > Commedia",
            "VOD > FILM > Crime e Thriller", "VOD > FILM > Horror",
            "VOD > FILM > Fantascienza e Fantasy", "VOD > FILM > Animazione e Famiglia",
            "VOD > FILM > Documentari e Musical", "VOD > FILM > Drammatici e Storici",
            "VOD > FILM > Altri film",
            "VOD > FILM > Romantici", "VOD > FILM > Documentari", "VOD > FILM > Famiglia e Bambini",
            "VOD > FILM > Storici e Guerra", "VOD > FILM > Western", "VOD > FILM > Musicali",
            "VOD > FILM > Reality e Show", "VOD > FILM > Produzioni Italiane", "VOD > FILM > Internazionali",
            "VOD > SERIE > Crime e Thriller", "VOD > SERIE > Commedia",
            "VOD > SERIE > Horror", "VOD > SERIE > Fantascienza e Fantasy",
            "VOD > SERIE > Azione e Avventura", "VOD > SERIE > Anime e Animazione",
            "VOD > SERIE > Documentari e Reality", "VOD > SERIE > Drammatiche",
            "VOD > SERIE > Altre serie",
            "VOD > SERIE > Romantici", "VOD > SERIE > Documentari", "VOD > SERIE > Famiglia e Bambini",
            "VOD > SERIE > Storici e Guerra", "VOD > SERIE > Western", "VOD > SERIE > Musicali",
            "VOD > SERIE > Reality e Show", "VOD > SERIE > Produzioni Italiane", "VOD > SERIE > Internazionali"
        )
        val legacy = listOf(
            "Sport", "Cinema", "Serie TV", "Documentari", "Bambini",
            "News", "Musica", "Intrattenimento", "XXX", "Da classificare", "Non classificati"
        )
        return path in generated || legacy.any { path == it || path.startsWith("$it > ") }
    }

    private fun inferCategoryPath(channel: TvChannel): String {
        val source = "${channel.name} ${channel.category}".lowercase(Locale.ROOT)
            .replace('_', ' ').replace('-', ' ')
        fun has(vararg terms: String) = terms.any { source.contains(it) }

        val rules = listOf(
            "Sport > Calcio" to arrayOf("calcio", "football", "soccer", "serie a", "serie b", "champions", "europa league", "conference league", "premier league", "liga", "bundesliga", "ligue 1", "coppa italia", "mondiale", "europei"),
            "Sport > Motori" to arrayOf("formula 1", "formula1", "f1", "motogp", "moto gp", "superbike", "wrc", "rally", "motori", "nascar"),
            "Sport > Tennis" to arrayOf("tennis", "atp", "wta", "roland garros", "wimbledon"),
            "Sport > Basket" to arrayOf("basket", "basketball", "nba", "euroleague"),
            "Sport > Altri sport" to arrayOf("sport", "dazn", "eurosport", "sky sport", "sportitalia", "golf", "rugby", "volley", "pallavolo", "ciclismo", "cycling", "olimpiadi"),
            "Cinema > Cinema italiano" to arrayOf("cinema italiano", "film italiani"),
            "Cinema > Cinema internazionale" to arrayOf("cinema", "sky cinema", "paramount", "film", "movies", "movie", "premiere", "action", "thriller", "comedy"),
            "Serie TV > Serie italiane" to arrayOf("serie italian", "fiction", "rai fiction"),
            "Serie TV > Serie internazionali" to arrayOf("serie tv", "series", "tv series", "drama", "crime", "investigation", "fox", "hbo", "amc"),
            "Documentari > Natura" to arrayOf("natura", "animal", "wild", "discovery", "national geographic", "nat geo", "wildlife"),
            "Documentari > Storia e Scienza" to arrayOf("history", "storia", "science", "scienza", "focus", "documentar"),
            "Bambini > Cartoni animati" to arrayOf("cartoon", "cartoni", "boing", "k2", "nick", "nickelodeon", "cartoonito", "disney junior"),
            "Bambini > Ragazzi" to arrayOf("disney channel", "teen", "ragazzi", "kids", "baby tv"),
            "News > Italiane" to arrayOf("tg", "rai news", "sky tg", "tgcom", "la7 news", "all news"),
            "News > Internazionali" to arrayOf("bbc news", "cnn", "fox news", "al jazeera", "france 24", "dw news", "euronews"),
            "Musica > Canali musicali" to arrayOf("music", "musica", "mtv", "radio", "concert"),
            "Intrattenimento > Generalisti" to arrayOf("rai 1", "rai 2", "rai 3", "canale 5", "italia 1", "rete 4", "la7", "nove", "tv8", "real time", "dmax"),
            "Intrattenimento > Reality e Show" to arrayOf("reality", "show", "talent", "masterchef", "amici", "gf", "grande fratello", "x factor", "talk")
        )
        for ((path, terms) in rules) if (has(*terms)) return path

        val knownPath = normalizeCategoryPath(channel.category)
        if (knownPath.contains(" > ")) return knownPath
        return when {
            has("intrattenimento", "entertainment") -> "Intrattenimento > Altri programmi"
            has("xxx", "adult") -> "XXX > Adult"
            else -> "Da classificare"
        }
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (importInProgress) { toast("È già in corso un'importazione."); return }
        importInProgress = true
        val destination = pendingImportCategory
        updateImportProgress("Preparazione importazione di ${uris.size} file…", null)
        Thread {
            val parsedFiles = mutableListOf<List<TvChannel>>()
            var invalid = 0
            uris.forEachIndexed { index, uri ->
                try {
                    updateImportProgress("Lettura file ${index + 1}/${uris.size}…", null)
                    val bytes = readImportBytes(uri) { total ->
                        updateImportProgress("Lettura file ${index + 1}/${uris.size} · ${total / (1024 * 1024)} MB", null)
                    }
                    val displayName = queryDisplayName(uri)
                    updateImportProgress("Analisi ${displayName} · file ${index + 1}/${uris.size}", (index * 100 / uris.size).coerceIn(0, 99))
                    val found = parseChannels(String(bytes, StandardCharsets.UTF_8)) { parsed ->
                        val overall = ((index * 100L + parsed) / uris.size).toInt()
                        updateImportProgress("Analisi ${displayName} · $parsed%", overall.coerceIn(0, 99))
                    }
                    if (found.isEmpty()) invalid++ else {
                        savePlaylistCopy(displayName, bytes)
                        parsedFiles.add(found)
                    }
                } catch (e: Exception) {
                    invalid++
                    Log.w("DAVIX-IMPORT", "Importazione file non riuscita: " + e.javaClass.simpleName)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) { importInProgress = false; return@runOnUiThread }
                val knownKeys = channels.asSequence()
                    .map { it.name.lowercase(Locale.ROOT) + "|" + it.url }
                    .toHashSet()
                val knownCategories = categories.toHashSet()
                val savedCategoryMapping = runCatching {
                    JSONObject(categoryPrefs.getString("channel_categories_json", "{}") ?: "{}")
                }.getOrDefault(JSONObject())
                val channelIterator = parsedFiles.asSequence().flatten().iterator()
                var added = 0
                var duplicates = 0
                var processed = 0
                val totalChannels = parsedFiles.sumOf { it.size }
                val batchSize = 200
                val importHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val importBatch = object : Runnable {
                    override fun run() {
                        if (isFinishing || isDestroyed) {
                            importInProgress = false
                            return
                        }
                        var inBatch = 0
                        while (channelIterator.hasNext() && inBatch < batchSize) {
                            val channel = channelIterator.next()
                            val savedCategory = savedCategoryMapping.optString(channel.url)
                            if (savedCategory.isNotBlank()) channel.category = normalizeCategoryPath(savedCategory)
                            channel.category = if (destination != null) {
                                destination
                            } else {
                                val savedManualPath = normalizeCategoryPath(channel.category)
                                val inferred = inferCategoryPath(channel)
                                if (savedManualPath.contains(" > ") && savedManualPath in knownCategories) savedManualPath else inferred
                            }
                            if (knownCategories.add(channel.category)) categories.add(channel.category)
                            val key = channel.name.lowercase(Locale.ROOT) + "|" + channel.url
                            if (!knownKeys.add(key)) {
                                duplicates++
                            } else {
                                channel.selected = !(channel.name.contains("sky", true) && (channel.name.contains("plus", true) || channel.name.contains("+1")))
                                channels.add(channel)
                                added++
                            }
                            processed++
                            inBatch++
                        }
                        if (channelIterator.hasNext()) {
                            updateImportProgress("Inserimento canali · $processed / $totalChannels", (processed * 99 / totalChannels.coerceAtLeast(1)).coerceIn(1, 99))
                            importHandler.postDelayed(this, 1L)
                        } else {
                            if (destination != null && knownCategories.add(destination)) categories.add(destination)
                            pendingImportCategory = null
                            importInProgress = false
                            finishImportProgress()
                            renderChannels()
                            saveCategorySettings()
                            persistChannelLibrary()
                            renderSavedPlaylists()
                            status.text = "File analizzati: ${uris.size} · Nuovi canali: $added · Duplicati ignorati: $duplicates · File senza canali validi: $invalid"
                        }
                    }
                }
                importHandler.post(importBatch)
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "playlist"
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/') ?: "playlist"
        }
    }

    private fun savedPlaylistDirectory(): java.io.File =
        java.io.File(filesDir, "saved_playlists").apply { mkdirs() }

    private fun savePlaylistCopy(originalName: String, bytes: ByteArray) {
        val safeName = originalName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "playlist.txt" }.take(80)
        val target = java.io.File(savedPlaylistDirectory(), "${System.currentTimeMillis()}_$safeName")
        target.writeBytes(bytes)
    }

    private fun renderSavedPlaylists() {
        if (!::savedPlaylistsList.isInitialized) return
        savedPlaylistsList.removeAllViews()
        val files = savedPlaylistDirectory().listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Nessuna playlist salvata."
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, dp(4), 0, dp(8))
            }
            savedPlaylistsList.addView(empty)
            return
        }
        files.forEach { file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setBackgroundColor(PANEL)
            }
            val name = TextView(this).apply {
                text = file.name.substringAfter('_')
                textSize = 12f
                setTextColor(Color.WHITE)
                maxLines = 2
            }
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(buttonView("Carica") { loadSavedPlaylist(file) })
            savedPlaylistsList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })
        }
    }

    private fun loadSavedPlaylist(file: java.io.File) {
        if (importInProgress) { toast("È già in corso un'importazione."); return }
        if (!file.exists()) {
            toast("La playlist salvata non è più disponibile.")
            renderSavedPlaylists()
            return
        }
        importInProgress = true
        updateImportProgress("Caricamento playlist salvata · lettura file…", null)
        Thread {
            try {
                val found = parseChannels(file.readText(StandardCharsets.UTF_8)) { parsed ->
                    updateImportProgress("Analisi playlist salvata · $parsed%", parsed)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) { importInProgress = false; return@runOnUiThread }
                    if (found.isEmpty()) {
                        status.text = "Nessun canale valido trovato nel file salvato."
                        toast(status.text.toString())
                    } else {
                        restoreChannelCategories(found)
                        val knownKeys = channels.asSequence().map { it.name.lowercase(Locale.ROOT) + "|" + it.url }.toHashSet()
                        var added = 0
                        var duplicates = 0
                        found.forEachIndexed { index, channel ->
                            if (index % 250 == 0) updateImportProgress("Inserimento canali · ${index + 1} / ${found.size}", 90 + index * 10 / found.size.coerceAtLeast(1))
                            if (!knownKeys.add(channel.name.lowercase(Locale.ROOT) + "|" + channel.url)) duplicates++
                            else {
                                channel.selected = !(channel.name.contains("sky", true) && (channel.name.contains("plus", true) || channel.name.contains("+1")))
                                channels.add(channel)
                                if (channel.category !in categories) categories.add(channel.category)
                                added++
                            }
                        }
                        status.text = "Playlist caricata: ${file.name.substringAfter('_')} · Nuovi canali: $added · Duplicati ignorati: $duplicates"
                        renderChannels()
                    }
                    importInProgress = false
                    finishImportProgress()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    importInProgress = false
                    finishImportProgress()
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Impossibile caricare la playlist: ${e.message}"
                        toast(status.text.toString())
                    }
                }
            }
        }.start()
    }

    private fun confirmClearSavedPlaylists() {
        AlertDialog.Builder(this)
            .setTitle("Svuotare l'archivio?")
            .setMessage("Verranno eliminate tutte le playlist salvate sul dispositivo e rimossi i canali attualmente caricati. Questa operazione non può essere annullata.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Svuota") { _, _ ->
                savedPlaylistDirectory().listFiles()?.forEach { it.delete() }
                channels.clear()
                categories.clear()
                categories.addAll(listOf("Intrattenimento", "Sport", "Cinema", "Documentari", "Musica", "XXX", "Non classificati"))
                if (::player.isInitialized) {
                    player.stop()
                    player.clearMediaItems()
                }
                currentPlayingChannelUrl = null
                if (::currentChannelLabel.isInitialized) currentChannelLabel.text = "NESSUN CANALE IN RIPRODUZIONE"
                renderChannels()
                renderSavedPlaylists()
                status.text = "Archivio svuotato. Puoi caricare una nuova playlist."
                toast("Archivio e lista canali svuotati.")
            }
            .show()
    }

    // Evita di caricare file arbitrariamente grandi in memoria durante l'importazione.
    private fun readImportBytes(uri: Uri, onProgress: ((Long) -> Unit)? = null): ByteArray {
        val maxBytes = 64L * 1024L * 1024L
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Impossibile aprire il file selezionato.")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    throw IOException("Il file supera il limite di importazione di 64 MB.")
                }
                output.write(buffer, 0, count)
                if (total % (512 * 1024) < count) onProgress?.invoke(total)
            }
            return output.toByteArray()
        }
    }

    private fun parseChannels(text: String, onProgress: ((Int) -> Unit)? = null): List<TvChannel> {
        val found = mutableListOf<TvChannel>()
        val seen = mutableSetOf<String>()
        fun add(nameRaw: String?, urlRaw: String?, logoRaw: String? = "", categoryRaw: String? = null, tvgIdRaw: String? = null, tvgNameRaw: String? = null) {
            val name = nameRaw?.trim()?.takeIf { it.isNotBlank() } ?: return
            val url = urlRaw?.trim()?.replace("&amp;", "&")?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return
            val key = name.lowercase(Locale.ROOT) + "|" + url
            if (seen.add(key)) found.add(TvChannel(
                name, url, logoRaw?.trim().orEmpty(),
                category = categoryRaw?.trim()?.takeIf { it.isNotBlank() } ?: "Non classificati",
                tvgId = tvgIdRaw?.trim().orEmpty(), tvgName = tvgNameRaw?.trim().orEmpty()
            ))
        }

        // Scansione lineare: cercare la URL con lines.drop(i + 1) per ogni
        // #EXTINF rendeva il parser quadratico sulle playlist IPTV molto grandi.
        val totalLines = (text.count { it == '\n' } + 1).coerceAtLeast(1)
        var processedLines = 0
        val lines = text.lineSequence()
        var pendingName: String? = null
        var pendingLogo = ""
        var pendingGroup: String? = null
        var pendingTvgId = ""
        var pendingTvgName = ""
        for (rawLine in lines) {
            processedLines++
            if (processedLines % 250 == 0) onProgress?.invoke((processedLines * 100 / totalLines).coerceIn(0, 99))
            val line = rawLine.trim()
            if (line.startsWith("#EXTINF", true)) {
                pendingName = line.substringAfterLast(',').trim()
                pendingLogo = Regex("tvg-logo=[\"']([^\"']+)").find(line)?.groupValues?.getOrNull(1).orEmpty()
                pendingGroup = Regex("group-title=[\"']([^\"']+)").find(line)?.groupValues?.getOrNull(1)
                pendingTvgId = Regex("tvg-id=[\"']([^\"']*)").find(line)?.groupValues?.getOrNull(1).orEmpty()
                pendingTvgName = Regex("tvg-name=[\"']([^\"']*)").find(line)?.groupValues?.getOrNull(1).orEmpty()
            } else if (pendingName != null && line.isNotEmpty() && !line.startsWith("#")) {
                add(pendingName, line, pendingLogo, pendingGroup, pendingTvgId, pendingTvgName)
                pendingName = null
                pendingLogo = ""
                pendingGroup = null
                pendingTvgId = ""
                pendingTvgName = ""
            }
        }

        fun field(obj: String, keys: List<String>): String? {
            for (key in keys) {
                val pattern = Regex("[\"']" + Regex.escape(key) + "[\"']\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                pattern.find(obj)?.groupValues?.getOrNull(1)?.let { return it }
            }
            return null
        }
        // Parser HTML essenziale per link diretti a flussi: mantiene il testo del link come nome.
        val htmlLinks = Regex("(?is)<a\\b[^>]*href\\s*=\\s*[\"'](https?://[^\"']+)[\"'][^>]*>(.*?)</a>")
        htmlLinks.findAll(text).forEach { match ->
            val label = android.text.Html.fromHtml(
                match.groupValues[2].replace(Regex("<[^>]+>"), " "),
                android.text.Html.FROM_HTML_MODE_LEGACY
            ).toString().trim()
            add(label, match.groupValues[1])
        }

        val objects = Regex("\\{[^{}]{0,4000}\\}").findAll(text)
        objects.forEach { m ->
            val obj = m.value
            add(
                field(obj, listOf("name", "title", "channel_name", "tvg-name")),
                field(obj, listOf("url", "stream", "src", "link")),
                field(obj, listOf("logo", "tvg-logo", "image", "icon")),
                field(obj, listOf("category", "group", "group-title")),
                field(obj, listOf("tvg-id", "tvgId", "epg_id")),
                field(obj, listOf("tvg-name", "tvgName"))
            )
        }

        try {
            val trimmed = text.trim()
            val rootObject = if (trimmed.startsWith("{")) JSONObject(trimmed) else null
            val array = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                rootObject != null -> rootObject.optJSONArray("channels")
                    ?: rootObject.optJSONArray("items")
                    ?: rootObject.optJSONArray("data")
                else -> null
            }
            if (array != null) for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                add(
                    o.optString("name", o.optString("title", o.optString("channel_name"))),
                    o.optString("url", o.optString("stream", o.optString("src", o.optString("link")))),
                    o.optString("logo", o.optString("tvg-logo")),
                    o.optString("category", o.optString("group", o.optString("group-title"))),
                    o.optString("tvg-id", o.optString("tvgId", o.optString("epg_id"))),
                    o.optString("tvg-name", o.optString("tvgName"))
                )
            }
        } catch (_: Exception) { }
        onProgress?.invoke(100)
        return found
    }

    private fun renderChannels() {
        // Il rendering non deve serializzare migliaia di URL su disco ad ogni
        // click/espansione: il salvataggio avviene nei punti di modifica e onStop.
        selectedList.removeAllViews()
        otherList.removeAllViews()
        val selected = channels.withIndex().filter { it.value.selected }
        val others = channels.withIndex().filter { !it.value.selected }
        // Costruisce una sola mappa degli indici per cartella e antenati.
        // Evita la precedente scansione completa di channels per ogni singola cartella.
        val bulkIndicesByPath = mutableMapOf<String, MutableList<Int>>()
        if (bulkSelectionMode) {
            channels.forEachIndexed { index, channel ->
                val parts = normalizeCategoryPath(channel.category).split(" > ").filter { it.isNotBlank() }
                var path = ""
                parts.forEach { part ->
                    path = if (path.isBlank()) part else "$path > $part"
                    bulkIndicesByPath.getOrPut(path) { mutableListOf() }.add(index)
                }
            }
        }
        selectedHeading.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        otherHeading.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE

        fun addChannelRow(index: Int, channel: TvChannel, target: LinearLayout) {
            val isPlayingChannel = currentPlayingChannelUrl == channel.url
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setBackgroundColor(if (isPlayingChannel) 0xFF173A4D.toInt() else PANEL)
            }
            val check = CheckBox(this).apply {
                isChecked = channel.selected
                buttonTintList = android.content.res.ColorStateList.valueOf(CYAN)
                setOnCheckedChangeListener { _, checked ->
                    channel.selected = checked
                    renderChannels()
                }
            }
            row.addView(check)
            if (bulkSelectionMode) {
                val bulkCheck = CheckBox(this).apply {
                    isChecked = index in bulkSelectedIndices
                    contentDescription = "Seleziona canale per lo spostamento"
                    buttonTintList = android.content.res.ColorStateList.valueOf(0xFFFFC857.toInt())
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) bulkSelectedIndices.add(index) else bulkSelectedIndices.remove(index)
                        updateCount()
                    }
                }
                row.addView(bulkCheck)
            }
            val play = Button(this).apply {
                text = if (isPlayingChannel) "▶  IN ONDA · ${channel.name}" else "▶  ${channel.name}"
                isAllCaps = false
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setTextColor(if (isPlayingChannel) CYAN else Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { playChannel(index) }
            }
            row.addView(play, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(buttonView(channel.category) { showCategoryPicker(channel) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
            target.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })
        }
        fun addFolderGroups(items: List<IndexedValue<TvChannel>>, target: LinearLayout, prefix: String) {
            data class FolderNode(
                val name: String,
                val path: String,
                val entries: MutableList<IndexedValue<TvChannel>> = mutableListOf(),
                val children: MutableMap<String, FolderNode> = linkedMapOf()
            )
            val rootNode = FolderNode("", "")
            items.forEach { entry ->
                val parts = normalizeCategoryPath(entry.value.category)
                    .ifBlank { "Da classificare" }.split(" > ").filter { it.isNotBlank() }
                var node = rootNode
                parts.forEach { part ->
                    val path = if (node.path.isBlank()) part else "${node.path} > $part"
                    node = node.children.getOrPut(part) { FolderNode(part, path) }
                }
                node.entries.add(entry)
            }

            fun countChannels(node: FolderNode): Int =
                node.entries.size + node.children.values.sumOf { countChannels(it) }

            fun renderFolderNode(node: FolderNode, depth: Int) {
                val key = "$prefix:${node.path}"
                val expanded = key in expandedCategoryPaths
                val total = countChannels(node)
                val header = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12 + depth * 16), dp(10), dp(10), dp(10))
                    setBackgroundColor(if (depth == 0) 0xFF1B2A40.toInt() else 0xFF162235.toInt())
                    isClickable = true
                    isFocusable = true
                    contentDescription = "${if (expanded) "Chiudi" else "Apri"} cartella ${node.path}, $total canali"
                    setOnClickListener {
                        if (expanded) expandedCategoryPaths.remove(key) else expandedCategoryPaths.add(key)
                        renderChannels()
                    }
                }
                header.addView(TextView(this).apply {
                    text = if (expanded) "📂" else "📁"
                    textSize = 16f
                })
                header.addView(TextView(this).apply {
                    text = node.name
                    textSize = if (depth == 0) 14f else 13f
                    setTextColor(Color.WHITE)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(9), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                header.addView(TextView(this).apply {
                    text = "$total  ·  ${if (expanded) "⌃" else "⌄"}"
                    textSize = 12f
                    setTextColor(CYAN)
                })
                if (bulkSelectionMode) {
                    val folderIndices = bulkIndicesByPath[node.path].orEmpty()
                    val allFolderSelected = folderIndices.isNotEmpty() &&
                        folderIndices.all { it in bulkSelectedIndices }
                    header.addView(buttonView(
                        if (allFolderSelected) "Deseleziona" else "Seleziona"
                    ) {
                        if (allFolderSelected) {
                            bulkSelectedIndices.removeAll(folderIndices.toSet())
                        } else {
                            bulkSelectedIndices.addAll(folderIndices)
                        }
                        renderChannels()
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                        leftMargin = dp(8)
                    })
                }
                target.addView(header, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(4)
                    topMargin = dp(if (depth == 0) 5 else 2)
                })

                if (expanded) {
                    val visibleLimit = (folderPageSizes[key] ?: CHANNELS_PER_FOLDER_PAGE)
                        .coerceAtLeast(CHANNELS_PER_FOLDER_PAGE)
                    node.entries.take(visibleLimit).forEach { entry ->
                        val holder = LinearLayout(this).apply {
                            setPadding(dp(10 + (depth + 1) * 14), 0, 0, 0)
                        }
                        addChannelRow(entry.index, entry.value, holder)
                        target.addView(holder, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ))
                    }
                    if (node.entries.size > visibleLimit) {
                        val remaining = node.entries.size - visibleLimit
                        target.addView(buttonView(
                            "Mostra altri ${minOf(CHANNELS_PER_FOLDER_PAGE, remaining)} · $remaining rimanenti"
                        ) {
                            folderPageSizes[key] = visibleLimit + CHANNELS_PER_FOLDER_PAGE
                            renderChannels()
                        }, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
                        ).apply { bottomMargin = dp(6) })
                    }
                    node.children.values.sortedBy { it.name.lowercase(Locale.ROOT) }
                        .forEach { child -> renderFolderNode(child, depth + 1) }
                }
            }

            rootNode.children.values.sortedBy { it.name.lowercase(Locale.ROOT) }
                .forEach { node -> renderFolderNode(node, 0) }
        }
        addFolderGroups(selected, selectedList, "selected")
        addFolderGroups(others, otherList, "other")
        updateCount()
    }

    private fun moveBulkSelectedChannels() {
        if (bulkSelectedIndices.isEmpty()) {
            toast("Seleziona prima i canali da spostare.")
            return
        }
        val options = categories.filter { it != "Non classificati" } + "＋  Crea nuova sezione / sottocartella…"
        AlertDialog.Builder(this)
            .setTitle("Sposta ${bulkSelectedIndices.size} canali in")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == options.lastIndex) {
                    val input = EditText(this).apply {
                        hint = "Intrattenimento > Cinema"
                        setSingleLine(true)
                        filters = arrayOf(android.text.InputFilter.LengthFilter(80))
                    }
                    AlertDialog.Builder(this).setTitle("Destinazione personalizzata").setView(input)
                        .setNegativeButton("Annulla", null)
                        .setPositiveButton("Sposta") { _, _ ->
                            val path = normalizeCategoryPath(input.text.toString())
                            if (path.isBlank()) { toast("Percorso non valido."); return@setPositiveButton }
                            applyBulkCategory(path)
                        }.show()
                } else applyBulkCategory(options[which])
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun applyBulkCategory(path: String) {
        if (path !in categories) categories.add(path)
        bulkSelectedIndices.toList().forEach { index -> channels.getOrNull(index)?.category = path }
        val moved = bulkSelectedIndices.size
        bulkSelectedIndices.clear()
        bulkSelectionMode = false
        renderChannels()
        toast("$moved canali spostati in $path")
    }

    private fun normalizeCategoryPath(raw: String): String =
        raw.split(">").map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }.joinToString(" > ").take(80)

    private fun showCategoryManager() {
        val options = listOf("＋  Nuova sezione / sottocartella") + categories.filter { it != "Non classificati" }
        AlertDialog.Builder(this).setTitle("Gestisci categorie").setItems(options.toTypedArray()) { _, index ->
            if (index == 0) promptNewCategory() else showCategoryActions(options[index])
        }.setNegativeButton("Chiudi", null).show()
    }

    private fun promptNewCategory(assignTo: TvChannel? = null) {
        val input = EditText(this).apply { hint = "Intrattenimento > Cinema"; setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(80)) }
        AlertDialog.Builder(this).setTitle("Nuova categoria").setView(input).setNegativeButton("Annulla", null)
            .setPositiveButton("Crea") { _, _ ->
                val name = normalizeCategoryPath(input.text.toString())
                if (name.isBlank() || categories.any { it.equals(name, true) }) { toast("Nome vuoto o categoria già esistente."); return@setPositiveButton }
                categories.add(name); assignTo?.category = name; renderChannels(); toast("Categoria creata: $name")
            }.show()
    }

    private fun showCategoryPicker(channel: TvChannel) {
        val options = categories.toList() + "＋  Crea nuova categoria…"
        AlertDialog.Builder(this).setTitle("Categoria · ${channel.name}").setItems(options.toTypedArray()) { _, index ->
            if (index == options.lastIndex) promptNewCategory(channel) else { channel.category = options[index]; renderChannels() }
        }.setNegativeButton("Annulla", null).show()
    }

    private fun showCategoryActions(category: String) {
        AlertDialog.Builder(this).setTitle(category).setItems(arrayOf("Rinomina", "Elimina")) { _, action ->
            val prefix = "$category > "
            if (action == 0) {
                val input = EditText(this).apply {
                    setText(category)
                    setSingleLine(true)
                    filters = arrayOf(android.text.InputFilter.LengthFilter(80))
                }
                AlertDialog.Builder(this).setTitle("Rinomina sezione / sottocartella").setView(input)
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton("Salva") { _, _ ->
                        val renamed = normalizeCategoryPath(input.text.toString())
                        val conflict = categories.any { it != category && it.equals(renamed, true) }
                        if (renamed.isBlank() || conflict) {
                            toast("Nome vuoto o già utilizzato.")
                        } else {
                            categories.indices.forEach { i ->
                                val current = categories[i]
                                if (current == category) categories[i] = renamed
                                else if (current.startsWith(prefix, true)) categories[i] = renamed + current.substring(category.length)
                            }
                            channels.forEach { channel ->
                                if (channel.category == category) channel.category = renamed
                                else if (channel.category.startsWith(prefix, true)) channel.category = renamed + channel.category.substring(category.length)
                            }
                            renderChannels()
                        }
                    }.show()
            } else {
                AlertDialog.Builder(this).setTitle("Eliminare $category?")
                    .setMessage("I canali della sezione e delle relative sottocartelle verranno spostati in Non classificati.")
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton("Elimina") { _, _ ->
                        categories.removeAll { it == category || it.startsWith(prefix, true) }
                        channels.filter { it.category == category || it.category.startsWith(prefix, true) }
                            .forEach { it.category = "Non classificati" }
                        renderChannels()
                    }.show()
            }
        }.setNegativeButton("Indietro", null).show()
    }

    private fun channelLibraryFile(): java.io.File =
        java.io.File(filesDir, "channel_library_v1.json")

    private fun channelLibraryAtomicFile(): AtomicFile =
        AtomicFile(channelLibraryFile())

    /**
     * Cattura uno snapshot immutabile sul main thread e delega serializzazione e I/O
     * al writer dedicato. La coda FIFO mantiene l'ordine degli snapshot.
     */
    private fun persistChannelLibrary() {
        val channelSnapshot = channels.map { channel ->
            ChannelArchiveEntry(
                channel.name, channel.url, channel.logo, channel.selected,
                channel.category, channel.tvgId, channel.tvgName
            )
        }
        val expandedSnapshot = expandedCategoryPaths.toList()
        val savedAt = System.currentTimeMillis()

        runCatching {
            archiveExecutor.execute {
                runCatching {
                    val savedChannels = JSONArray()
                    channelSnapshot.forEach { channel ->
                        savedChannels.put(JSONObject().apply {
                            put("name", channel.name)
                            put("url", channel.url)
                            put("logo", channel.logo)
                            put("selected", channel.selected)
                            put("category", channel.category)
                            put("tvgId", channel.tvgId)
                            put("tvgName", channel.tvgName)
                        })
                    }
                    val expanded = JSONArray().apply { expandedSnapshot.forEach { put(it) } }
                    val state = JSONObject().apply {
                        // Mantiene il formato v1: gli archivi già presenti restano leggibili.
                        put("version", 1)
                        put("channels", savedChannels)
                        put("expandedCategories", expanded)
                        put("savedAt", savedAt)
                    }
                    val atomicFile = channelLibraryAtomicFile()
                    val output = atomicFile.startWrite()
                    try {
                        output.write(state.toString().toByteArray(StandardCharsets.UTF_8))
                        atomicFile.finishWrite(output)
                    } catch (error: Throwable) {
                        atomicFile.failWrite(output)
                        throw error
                    }
                }.onFailure {
                    Log.w("DAVIX-ARCHIVE", "Salvataggio archivio canali non riuscito; la copia precedente viene conservata", it)
                }
            }
        }.onFailure {
            Log.w("DAVIX-ARCHIVE", "Impossibile accodare il salvataggio dell’archivio canali", it)
        }
    }

    /** Ripristina i canali prima di costruire l’interfaccia. AtomicFile recupera il backup di una scrittura interrotta. */
    private fun restoreChannelLibrary() {
        val file = channelLibraryFile()
        val interruptedWriteBackup = java.io.File(file.path + ".bak")
        if (!file.isFile && !interruptedWriteBackup.isFile) return
        runCatching {
            val state = channelLibraryAtomicFile().openRead().bufferedReader(StandardCharsets.UTF_8).use {
                JSONObject(it.readText())
            }
            val savedChannels = state.optJSONArray("channels") ?: JSONArray()
            val restored = mutableListOf<TvChannel>()
            for (i in 0 until savedChannels.length()) {
                val item = savedChannels.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val url = item.optString("url").trim()
                if (name.isBlank() || !(url.startsWith("https://", true) || url.startsWith("http://", true))) continue
                val category = normalizeCategoryPath(item.optString("category", "Non classificati")).ifBlank { "Non classificati" }
                restored.add(TvChannel(name, url, item.optString("logo"), item.optBoolean("selected", true), category, item.optString("tvgId"), item.optString("tvgName")))
                if (category !in categories) categories.add(category)
            }
            channels.clear()
            channels.addAll(restored)
            expandedCategoryPaths.clear()
            val expanded = state.optJSONArray("expandedCategories") ?: JSONArray()
            for (i in 0 until expanded.length()) expanded.optString(i).takeIf { it.isNotBlank() }?.let { expandedCategoryPaths.add(it) }
            Log.i("DAVIX-ARCHIVE", "Ripristinati " + channels.size + " canali dall’archivio locale")
        }.onFailure {
            // Non cancellare o sovrascrivere un archivio illeggibile: serve per diagnosi e recupero.
            Log.e("DAVIX-ARCHIVE", "Archivio canali non leggibile; file conservato per diagnosi", it)
        }
    }

    private fun loadCategorySettings() {
        val saved = categoryPrefs.getString("categories_json", null)
        if (!saved.isNullOrBlank()) runCatching {
            val array = JSONArray(saved)
            val loaded = (0 until array.length()).map { normalizeCategoryPath(array.optString(it)) }.filter { it.isNotBlank() && it.length <= 80 }.distinct()
            if (loaded.isNotEmpty()) { categories.clear(); categories.addAll(loaded) }
        }
        if ("Non classificati" !in categories) categories.add("Non classificati")
    }

    private fun restoreChannelCategories(items: List<TvChannel>) {
        val mapping = runCatching { JSONObject(categoryPrefs.getString("channel_categories_json", "{}") ?: "{}") }.getOrNull() ?: return
        items.forEach { channel ->
            val savedCategory = mapping.optString(channel.url)
            if (savedCategory.isNotBlank()) channel.category = normalizeCategoryPath(savedCategory)
            if (channel.category !in categories) categories.add(channel.category)
        }
    }

    private fun saveCategorySettings() {
        // Snapshot leggero sul main thread; JSON e aggiornamento preferenze sono serializzati sul writer.
        val categorySnapshot = categories.toList()
        val channelCategorySnapshot = channels.map { it.url to it.category }
        runCatching {
            archiveExecutor.execute {
                runCatching {
                    val mapping = JSONObject()
                    channelCategorySnapshot.forEach { (url, category) -> mapping.put(url, category) }
                    categoryPrefs.edit()
                        .putString("categories_json", JSONArray(categorySnapshot).toString())
                        .putString("channel_categories_json", mapping.toString())
                        .apply()
                }.onFailure {
                    Log.w("DAVIX-ARCHIVE", "Salvataggio impostazioni categorie non riuscito", it)
                }
            }
        }.onFailure {
            Log.w("DAVIX-ARCHIVE", "Impossibile accodare il salvataggio delle categorie", it)
        }
    }

    private fun updateCount() {
        count.text = "${channels.size} canali importati · ${channels.count { it.selected }} selezionati per esportazione"
    }

    private fun streamMimeType(rawUrl: String): String? {
        val path = runCatching { Uri.parse(rawUrl.substringBefore('|')).lastPathSegment.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".ism") || path.endsWith(".isml") -> MimeTypes.APPLICATION_SS
            else -> null
        }
    }

    private fun playChannel(index: Int) {
        val channel = channels.getOrNull(index) ?: return
        currentPlayingChannelUrl = channel.url
        if (::currentChannelLabel.isInitialized) currentChannelLabel.text = "▶  IN RIPRODUZIONE · ${channel.name}"
        renderChannels()
        playbackGeneration++
        status.text = "Avvio: ${channel.name}"
        try {
            playbackRetryTask?.let { retryHandler.removeCallbacks(it) }
            playbackRetryTask = null
            playbackRetryCount = 0
            // Imposta il MIME solo quando il formato è riconoscibile dall'URL.
            // Per gli altri flussi lascia decidere a Media3 (TS, MP4, ecc.).
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(channel.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).build())
            streamMimeType(channel.url)?.let { mediaItemBuilder.setMimeType(it) }
            val mediaItem = mediaItemBuilder
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(45_000)
                        .setMinOffsetMs(30_000)
                        .setMaxOffsetMs(90_000)
                        .setMinPlaybackSpeed(0.97f)
                        .setMaxPlaybackSpeed(1.03f)
                        .build()
                )
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            status.text = "Impossibile avviare il canale: ${e.message}"
        }
    }

    private fun selectedChannels() = channels.filter { it.selected }

    private fun requestHtmlExport() {
        val chosen = selectedChannels()
        if (chosen.isEmpty()) { toast("Seleziona almeno un canale."); return }
        AlertDialog.Builder(this)
            .setTitle("Preparare il Player HTML?")
            .setMessage("Per proteggere i canali, il file HTML non conterrà indirizzi o nomi della playlist. La selezione verrà pubblicata su DAVIX Cloud Sync e sarà visibile solo dopo l'attivazione con un codice monouso.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Pubblica ed esporta") { _, _ ->
                val payload = JSONArray()
                chosen.forEachIndexed { index, channel ->
                    payload.put(
                        JSONObject()
                            .put("id", "channel-$index")
                            .put("name", channel.name)
                            .put("url", channel.url)
                            .put("logo", channel.logo)
                            .put("category", channel.category)
                            .put("tvgId", channel.tvgId)
                            .put("tvgName", channel.tvgName)
                    )
                }
                runCloudAdminTask(
                    task = { CloudSyncAdmin.publish(this, payload, JSONArray(categories)) },
                    onSuccess = { result ->
                        pendingHtml = makePlayerHtml(chosen)
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/html"
                            putExtra(Intent.EXTRA_TITLE, "davix-player.html")
                        }
                        status.text = "Playlist pubblicata · ${result.optInt("channelCount")} canali. Scegli dove salvare il file HTML."
                        startActivityForResult(intent, SAVE_HTML)
                    }
                )
            }
            .show()
    }

    private fun makePlayerHtml(chosen: List<TvChannel>): String {
        // I canali non vengono incorporati nell'HTML distribuito: sono consegnati
        // dal Cloud Sync solo dopo l'attivazione monouso del singolo browser.
        val template = assets.open("player-template.html")
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
        check(!template.contains("__CHANNELS__") && template.contains("__CLOUD_SYNC_URL__")) {
            "Modello DAVIX Player non valido: i canali non devono essere incorporati e il segnaposto Cloud Sync è obbligatorio."
        }
        return template.replace("__CLOUD_SYNC_URL__", JSONObject.quote(CloudSyncAdmin.baseUrl(this)))
    }

    private fun toggleFullscreen() {
        if (!fullscreen) {
            // Contenitore dedicato: la PlayerView non diventa direttamente la root.
            (playerView.parent as? ViewGroup)?.removeView(playerView)
            fullscreenContainer.removeAllViews()
            fullscreenContainer.setBackgroundColor(Color.BLACK)
            fullscreenContainer.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val exitButton = Button(this).apply {
                text = "✕"
                textSize = 18f
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xCC26364D.toInt()))
                setOnClickListener { toggleFullscreen() }
            }
            fullscreenContainer.addView(exitButton, FrameLayout.LayoutParams(dp(52), dp(48), Gravity.TOP or Gravity.END).apply { topMargin = dp(12); marginEnd = dp(12) })
            fullscreen = true
            setContentView(fullscreenContainer)
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            fullscreen = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window.decorView.systemUiVisibility = 0
            (playerView.parent as? ViewGroup)?.removeView(playerView)
            fullscreenContainer.removeAllViews()
            setContentView(mainContainer)
            playerDock.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(176)))
        }
    }

    @Deprecated("Legacy back callback retained for broad Android compatibility")
    override fun onBackPressed() {
        if (fullscreen) toggleFullscreen() else super.onBackPressed()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStart() {
        super.onStart()
        retryHandler.removeCallbacks(playbackInfoTask)
        retryHandler.post(playbackInfoTask)
    }

    override fun onStop() {
        persistChannelLibrary()
        saveCategorySettings()
        retryHandler.removeCallbacks(playbackInfoTask)
        // Non lasciare il flusso attivo quando l'app passa davvero in background.
        // Il cambio orientamento è gestito da configChanges nel manifest.
        playbackRetryTask?.let { retryHandler.removeCallbacks(it) }
        playbackRetryTask = null
        stablePlaybackTask?.let { retryHandler.removeCallbacks(it) }
        stablePlaybackTask = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::player.isInitialized && (castPlayer == null || player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL)) player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        playbackRetryTask?.let { retryHandler.removeCallbacks(it) }
        retryHandler.removeCallbacksAndMessages(null)
        stablePlaybackTask = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::player.isInitialized) player.release()
        // Esegue anche gli snapshot già accodati prima di terminare il writer.
        archiveExecutor.shutdown()
        super.onDestroy()
    }
}
