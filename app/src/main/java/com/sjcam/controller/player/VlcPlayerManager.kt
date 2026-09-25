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

    private val _mediaTime = MutableStateFlow(0L)
    val mediaTime: StateFlow<Long> = _mediaTime.asStateFlow()

    private val _mediaLength = MutableStateFlow(0L)
    val mediaLength: StateFlow<Long> = _mediaLength.asStateFlow()

    private val _mediaPosition = MutableStateFlow(0f)
    val mediaPosition: StateFlow<Float> = _mediaPosition.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isPhoneRecording = MutableStateFlow(false)
    val isPhoneRecording: StateFlow<Boolean> = _isPhoneRecording.asStateFlow()

    private val _phoneRecordPath = MutableStateFlow<String?>(null)
    val phoneRecordPath: StateFlow<String?> = _phoneRecordPath.asStateFlow()

    var onRecordFinished: ((java.io.File) -> Unit)? = null

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
                        _mediaTime.value = event.timeChanged
                    }
                    MediaPlayer.Event.LengthChanged -> {
                        _mediaLength.value = event.lengthChanged
                    }
                    MediaPlayer.Event.PositionChanged -> {
                        _mediaPosition.value = event.positionChanged
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
                        _mediaPosition.value = 1f
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
                    MediaPlayer.Event.RecordChanged -> {
                        val rec = event.recording
                        val path = event.recordPath
                        AppLogger.rtsp(TAG, "VLC Event RecordChanged: recording=$rec, path=$path")
                        _isPhoneRecording.value = rec
                        if (rec) {
                            _phoneRecordPath.value = path
                        } else {
                            _phoneRecordPath.value = null
                            if (!path.isNullOrBlank()) {
                                val file = java.io.File(path)
                                if (file.exists() && file.length() > 0) {
                                    AppLogger.i(TAG, "Archivo de video grabado en celular: ${file.absolutePath} (${file.length()} bytes)")
                                    onRecordFinished?.invoke(file)
                                }
                            }
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
        val player = mediaPlayer ?: return
        if (currentLayout === layout && isViewsAttached) return

        try {
            if (isViewsAttached) {
                player.detachViews()
                isViewsAttached = false
            }
            currentLayout = layout
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

    /**
     * Reproduce un archivo de video grabado (HTTP o local file://)
     * sin opciones agresivas de RTSP live y sin el watchdog de reconexión.
     */
    fun playMediaFile(url: String) {
        AppLogger.i(TAG, "Iniciando reproducción de archivo de video en LibVLC: $url")
        _errorMessage.value = null
        shouldBePlaying = false
        watchdogJob?.cancel()
        watchdogJob = null

        initializePlayer()

        val player = mediaPlayer ?: return
        val vlc = libVLC ?: return

        try {
            player.stop()

            val media = Media(vlc, Uri.parse(url)).apply {
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=1500")
                addOption(":file-caching=1000")
            }

            player.media = media
            media.release()

            currentLayout?.let {
                attachLayout(it)
            }

            player.play()
            _isPlaying.value = true
            _mediaTime.value = 0L
            _mediaLength.value = 0L
            _mediaPosition.value = 0f
            AppLogger.i(TAG, "Reproducción de video iniciada en LibVLC.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reproduciendo archivo de video: ${e.message}", e)
            _errorMessage.value = e.message
        }
    }

    fun seekToPosition(ratio: Float) {
        val player = mediaPlayer ?: return
        try {
            val clamped = ratio.coerceIn(0f, 1f)
            player.position = clamped
            _mediaPosition.value = clamped
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error en seekToPosition: ${e.message}")
        }
    }

    fun seekToTime(timeMs: Long) {
        val player = mediaPlayer ?: return
        try {
            player.time = timeMs.coerceAtLeast(0L)
            _mediaTime.value = player.time
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error en seekToTime: ${e.message}")
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        try {
            if (player.isPlaying) {
                player.pause()
                _isPlaying.value = false
            } else {
                player.play()
                _isPlaying.value = true
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error en togglePlayPause: ${e.message}")
        }
    }

    fun jumpSeconds(deltaSec: Int) {
        val player = mediaPlayer ?: return
        try {
            val current = player.time
            val length = player.length
            val maxLen = if (length > 0) length else Long.MAX_VALUE
            val target = (current + deltaSec * 1000L).coerceIn(0L, maxLen)
            player.time = target
            _mediaTime.value = target
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error en jumpSeconds: ${e.message}")
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

    fun startPhoneRecording(targetDir: java.io.File): Boolean {
        initializePlayer()
        val player = mediaPlayer
        if (player == null) {
            AppLogger.e(TAG, "No se puede iniciar grabación: MediaPlayer es nulo.")
            return false
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        AppLogger.i(TAG, "Solicitando inicio de grabación local LibVLC en: ${targetDir.absolutePath}")
        return try {
            val started = player.record(targetDir.absolutePath)
            if (started) {
                _isPhoneRecording.value = true
                AppLogger.i(TAG, "¡LibVLC comenzó a escribir stream directo a disco local!")
            } else {
                AppLogger.w(TAG, "player.record devolvió false.")
            }
            started
        } catch (e: Exception) {
            AppLogger.e(TAG, "Excepción al iniciar player.record: ${e.message}", e)
            false
        }
    }

    fun stopPhoneRecording() {
        AppLogger.i(TAG, "Deteniendo grabación local LibVLC...")
        try {
            mediaPlayer?.record(null)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error al detener player.record: ${e.message}", e)
        }
        _isPhoneRecording.value = false
    }

    fun stopStream() {
        AppLogger.rtsp(TAG, "Deteniendo stream VLC RTSP.")
        if (_isPhoneRecording.value) {
            stopPhoneRecording()
        }
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
        if (_isPhoneRecording.value) {
            stopPhoneRecording()
        }
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
