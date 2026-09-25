package com.sjcam.controller.player

import android.content.Context
import android.net.Uri
import com.sjcam.controller.data.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class VlcPlayerManager(private val context: Context) {

    private val TAG = "VlcPlayer"

    private var libVLC: LibVLC? = null
    var mediaPlayer: MediaPlayer? = null
        private set

    private var currentLayout: VLCVideoLayout? = null
    private var isViewsAttached = false

    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watchdogJob: Job? = null
    private var shouldBePlaying = false
    private var lastPacketTimestamp = 0L
    private var lastRtspUrl: String? = null
    private var lastForceTcp: Boolean = true

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun initializePlayer() {
        if (mediaPlayer != null) return

        AppLogger.rtsp(TAG, "Inicializando LibVLC optimizado con watchdog de reconexión...")
        val args = arrayListOf(
            "--network-caching=250",
            "--live-caching=250",
            "--file-caching=250",
            "--drop-late-frames",
            "--skip-frames",
            "--avcodec-fast",
            "--avcodec-threads=2",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "-vvv"
        )
        try {
            val vlc = LibVLC(context, args)
            val player = MediaPlayer(vlc)

            player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

            player.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Opening -> {
                        AppLogger.rtsp(TAG, "VLC: Negociando RTSP con la cámara...")
                        lastPacketTimestamp = System.currentTimeMillis()
                    }
                    MediaPlayer.Event.Buffering -> {
                        val pct = event.buffering
                        lastPacketTimestamp = System.currentTimeMillis()
                        if (pct >= 100f) {
                            _isPlaying.value = true
                        }
                    }
                    MediaPlayer.Event.Playing -> {
                        AppLogger.rtsp(TAG, "¡VLC: Stream activo en reproducción!")
                        _isPlaying.value = true
                        _errorMessage.value = null
                        lastPacketTimestamp = System.currentTimeMillis()
                    }
                    MediaPlayer.Event.TimeChanged -> {
                        lastPacketTimestamp = System.currentTimeMillis()
                    }
                    MediaPlayer.Event.Paused -> {
                        _isPlaying.value = false
                    }
                    MediaPlayer.Event.Stopped -> {
                        _isPlaying.value = false
                    }
                    MediaPlayer.Event.EndReached -> {
                        AppLogger.rtsp(TAG, "VLC: Sesión RTSP finalizada o reiniciada por la cámara (EndReached).")
                        _isPlaying.value = false
                        if (shouldBePlaying) {
                            triggerAutoReconnect("EndReached de LIVE555")
                        }
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        val msg = "Aviso: Error en stream VLC RTSP."
                        AppLogger.w(TAG, msg)
                        _errorMessage.value = msg
                        _isPlaying.value = false
                        if (shouldBePlaying) {
                            triggerAutoReconnect("EncounteredError")
                        }
                    }
                    MediaPlayer.Event.Vout -> {
                        if (event.voutCount > 0) {
                            _isPlaying.value = true
                            lastPacketTimestamp = System.currentTimeMillis()
                        }
                    }
                }
            }

            libVLC = vlc
            mediaPlayer = player

            currentLayout?.let {
                attachLayout(it)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error inicializando LibVLC: ${e.message}", e)
            _errorMessage.value = "Error al iniciar LibVLC: ${e.message}"
        }
    }

    private fun triggerAutoReconnect(reason: String) {
        playerScope.launch {
            delay(800)
            if (shouldBePlaying) {
                val url = lastRtspUrl
                if (!url.isNullOrBlank()) {
                    AppLogger.rtsp(TAG, "Watchdog: Auto-reconectando stream ($reason)...")
                    startStream(url, lastForceTcp)
                }
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = playerScope.launch {
            while (isActive && shouldBePlaying) {
                delay(3000)
                val elapsed = System.currentTimeMillis() - lastPacketTimestamp
                if (shouldBePlaying && _isPlaying.value && elapsed > 6500) {
                    AppLogger.w(TAG, "Watchdog: Sin paquetes durante ${elapsed / 1000}s. Reiniciando conexión RTSP...")
                    triggerAutoReconnect("Packet timeout > 6s")
                }
            }
        }
    }

    fun attachLayout(layout: VLCVideoLayout) {
        currentLayout = layout
        val player = mediaPlayer ?: return
        if (isViewsAttached) return

        try {
            player.attachViews(layout, null, false, false)
            isViewsAttached = true
            AppLogger.rtsp(TAG, "VLCVideoLayout adjuntado correctamente.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error al adjuntar VLCVideoLayout: ${e.message}", e)
        }
    }

    fun detachLayout() {
        try {
            if (isViewsAttached) {
                mediaPlayer?.detachViews()
                isViewsAttached = false
                AppLogger.rtsp(TAG, "VLCVideoLayout desadjuntado.")
            }
            currentLayout = null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error al desadjuntar VLCVideoLayout: ${e.message}", e)
        }
    }

    fun startStream(rtspUrl: String, forceTcp: Boolean = true) {
        AppLogger.rtsp(TAG, "Iniciando stream VLC RTSP hacia: $rtspUrl (forceTcp=$forceTcp)")
        _errorMessage.value = null

        shouldBePlaying = true
        lastRtspUrl = rtspUrl
        lastForceTcp = forceTcp
        lastPacketTimestamp = System.currentTimeMillis()

        initializePlayer()

        val player = mediaPlayer ?: return
        val vlc = libVLC ?: return

        try {
            player.stop()

            val media = Media(vlc, Uri.parse(rtspUrl)).apply {
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=250")
                addOption(":live-caching=250")
                addOption(":clock-jitter=0")
                addOption(":clock-synchro=0")
                if (forceTcp) {
                    addOption(":rtsp-tcp")
                } else {
                    addOption(":rtsp-udp")
                }
            }

            player.media = media
            media.release()

            currentLayout?.let {
                attachLayout(it)
            }

            player.play()
            startWatchdog()
            AppLogger.rtsp(TAG, "VLC play() enviado. Negociando con servidor LIVE555...")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error iniciando stream VLC: ${e.message}", e)
            _errorMessage.value = e.message
        }
    }

    fun stopStream() {
        AppLogger.rtsp(TAG, "Deteniendo stream VLC RTSP.")
        shouldBePlaying = false
        watchdogJob?.cancel()
        watchdogJob = null
        try {
            mediaPlayer?.stop()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error al detener MediaPlayer: ${e.message}", e)
        }
        _isPlaying.value = false
    }

    fun release() {
        AppLogger.rtsp(TAG, "Liberando recursos de LibVLC...")
        shouldBePlaying = false
        watchdogJob?.cancel()
        watchdogJob = null
        try {
            mediaPlayer?.let { player ->
                player.stop()
                if (isViewsAttached) {
                    player.detachViews()
                    isViewsAttached = false
                }
                player.release()
            }
            mediaPlayer = null
            libVLC?.release()
            libVLC = null
            currentLayout = null
            _isPlaying.value = false
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error liberando LibVLC: ${e.message}", e)
        }
    }
}
