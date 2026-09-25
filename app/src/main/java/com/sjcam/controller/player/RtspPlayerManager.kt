package com.sjcam.controller.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.sjcam.controller.data.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
class RtspPlayerManager(private val context: Context) {

    private val TAG = "RtspPlayer"
    var player: ExoPlayer? = null
        private set

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun initializePlayer() {
        if (player != null) return

        AppLogger.rtsp(TAG, "Inicializando ExoPlayer con soporte RTSP...")
        val exoPlayer = ExoPlayer.Builder(context).build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateStr = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN ($playbackState)"
                }
                AppLogger.rtsp(TAG, "Estado de reproducción cambiado: $stateStr")
                _isPlaying.value = (playbackState == Player.STATE_READY && exoPlayer.playWhenReady)
            }

            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                _isPlaying.value = isPlayingNow
                AppLogger.rtsp(TAG, "isPlayWhenReady: $isPlayingNow")
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = "Error en reproductor RTSP: ${error.message} (code: ${error.errorCodeName})"
                AppLogger.e(TAG, msg, error)
                _errorMessage.value = msg
                _isPlaying.value = false
            }
        })

        player = exoPlayer
    }

    fun startStream(rtspUrl: String, forceTcp: Boolean = true) {
        AppLogger.rtsp(TAG, "Solicitando inicio de stream RTSP hacia: $rtspUrl (forceTcp=$forceTcp)")
        _errorMessage.value = null

        initializePlayer()

        val exo = player ?: return
        try {
            val mediaItem = MediaItem.fromUri(rtspUrl)
            val rtspMediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(forceTcp)
                .setDebugLoggingEnabled(true)
                .setTimeoutMs(8000)
                .createMediaSource(mediaItem)

            exo.setMediaSource(rtspMediaSource)
            exo.prepare()
            exo.playWhenReady = true
            AppLogger.rtsp(TAG, "ExoPlayer preparado y esperando paquetes...")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error configurando MediaSource RTSP: ${e.message}", e)
            _errorMessage.value = e.message
        }
    }

    fun stopStream() {
        AppLogger.rtsp(TAG, "Deteniendo stream RTSP.")
        player?.stop()
        player?.clearMediaItems()
        _isPlaying.value = false
    }

    fun release() {
        AppLogger.rtsp(TAG, "Liberando recursos de ExoPlayer.")
        player?.release()
        player = null
        _isPlaying.value = false
    }
}
