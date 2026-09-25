package com.sjcam.controller.media

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.sjcam.controller.data.AppLogger
import com.sjcam.controller.data.CameraMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MediaDownloadManager(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    private val TAG = "MediaDownloadManager"

    // Map de progreso de descarga por ruta relativa del archivo (0f..1f, o -1f para error)
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val _downloadedFiles = MutableStateFlow<Map<String, String>>(emptyMap()) // relativePath -> absoluteLocalPath
    val downloadedFiles: StateFlow<Map<String, String>> = _downloadedFiles.asStateFlow()

    init {
        checkExistingDownloads()
    }

    private fun getStorageDir(isVideo: Boolean): File {
        val publicDir = if (isVideo) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        }
        val target = File(publicDir, "SJCAM")
        if (!target.exists()) {
            val created = target.mkdirs()
            if (!created) {
                // Fallback a almacenamiento privado de la app si no hay acceso directo
                return File(context.getExternalFilesDir(if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), "SJCAM").apply { mkdirs() }
            }
        }
        return target
    }

    fun checkExistingDownloads() {
        val map = mutableMapOf<String, String>()
        val movieDir = getStorageDir(true)
        val picDir = getStorageDir(false)

        movieDir.listFiles()?.forEach { map[it.name] = it.absolutePath }
        picDir.listFiles()?.forEach { map[it.name] = it.absolutePath }

        _downloadedFiles.value = map
    }

    suspend fun downloadFile(item: CameraMediaItem, onFinished: (File?) -> Unit = {}) = withContext(Dispatchers.IO) {
        val targetDir = getStorageDir(item.isVideo)
        val destFile = File(targetDir, item.name)

        if (destFile.exists() && destFile.length() > 0) {
            AppLogger.i(TAG, "El archivo ya existe localmente: ${destFile.absolutePath}")
            _downloadProgress.update { it + (item.relativePath to 1.0f) }
            _downloadedFiles.update { it + (item.relativePath to destFile.absolutePath) }
            withContext(Dispatchers.Main) { onFinished(destFile) }
            return@withContext
        }

        AppLogger.i(TAG, "Descargando ${item.httpUrl} a ${destFile.absolutePath}...")
        _downloadProgress.update { it + (item.relativePath to 0.01f) }

        val request = Request.Builder().url(item.httpUrl).get().build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.e(TAG, "Error HTTP ${response.code} descargando ${item.name}")
                    _downloadProgress.update { it - item.relativePath }
                    withContext(Dispatchers.Main) { onFinished(null) }
                    return@withContext
                }

                val body = response.body ?: throw Exception("Cuerpo de respuesta vacío")
                val totalBytes = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(destFile)

                val buffer = ByteArray(32 * 1024)
                var downloaded: Long = 0
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        val progress = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        _downloadProgress.update { it + (item.relativePath to progress) }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                AppLogger.i(TAG, "Descarga completada: ${destFile.name} (${destFile.length()} bytes)")
                _downloadProgress.update { it + (item.relativePath to 1.0f) }
                _downloadedFiles.update { it + (item.relativePath to destFile.absolutePath) }

                // Indexar en la Galería del teléfono con MediaScanner
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf(if (item.isVideo) "video/mp4" else "image/jpeg"),
                    null
                )

                withContext(Dispatchers.Main) { onFinished(destFile) }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Excepción durante la descarga de ${item.name}: ${e.message}", e)
            _downloadProgress.update { it - item.relativePath }
            if (destFile.exists()) destFile.delete()
            withContext(Dispatchers.Main) { onFinished(null) }
        }
    }
}
