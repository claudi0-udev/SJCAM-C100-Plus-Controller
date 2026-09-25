package com.sjcam.controller.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
) {
    private val TAG = "MediaDownloadManager"

    // Map de progreso de descarga por ruta relativa del archivo (0f..1f, o -1f para error)
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val _downloadedFiles = MutableStateFlow<Map<String, String>>(emptyMap()) // fileName / relativePath -> absoluteLocalPath
    val downloadedFiles: StateFlow<Map<String, String>> = _downloadedFiles.asStateFlow()

    init {
        checkExistingDownloads()
    }

    private fun getStorageDir(isVideo: Boolean): File {
        // Carpeta local de la app
        val type = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val localDir = File(context.getExternalFilesDir(type), "SJCAM").apply { mkdirs() }
        return localDir
    }

    fun checkExistingDownloads() {
        val map = mutableMapOf<String, String>()
        val movieDir = getStorageDir(true)
        val picDir = getStorageDir(false)

        movieDir.listFiles()?.forEach { if (it.length() > 0) map[it.name] = it.absolutePath }
        picDir.listFiles()?.forEach { if (it.length() > 0) map[it.name] = it.absolutePath }

        _downloadedFiles.value = map
    }

    suspend fun downloadFile(
        item: CameraMediaItem,
        cameraBaseUrl: String,
        onFinished: (File?) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val targetDir = getStorageDir(item.isVideo)
        val destFile = File(targetDir, item.name)

        if (destFile.exists() && destFile.length() > 0) {
            AppLogger.i(TAG, "El archivo ya existe localmente: ${destFile.absolutePath}")
            _downloadProgress.update { it + (item.relativePath to 1.0f) }
            _downloadedFiles.update { it + (item.name to destFile.absolutePath) + (item.relativePath to destFile.absolutePath) }
            withContext(Dispatchers.Main) { onFinished(destFile) }
            return@withContext
        }

        val candidates = item.getCandidateUrls(cameraBaseUrl)
        AppLogger.i(TAG, "Iniciando descarga de ${item.name} (${candidates.size} URLs candidatas)...")
        _downloadProgress.update { it + (item.relativePath to 0.01f) }

        var success = false
        var lastError: Exception? = null

        for (url in candidates) {
            AppLogger.i(TAG, "Probando descarga desde: $url")
            val request = Request.Builder().url(url).get().build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        AppLogger.w(TAG, "Fallo HTTP ${response.code} en $url")
                        return@use
                    }

                    val body = response.body ?: return@use
                    val totalBytes = body.contentLength()
                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(destFile)

                    val buffer = ByteArray(64 * 1024)
                    var downloaded: Long = 0
                    var read: Int

                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            val progress = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0.01f, 0.99f)
                            _downloadProgress.update { it + (item.relativePath to progress) }
                        }
                    }

                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()

                    if (destFile.length() > 0) {
                        success = true
                        AppLogger.i(TAG, "¡Descarga completada con éxito! ${destFile.name} (${destFile.length()} bytes)")
                        return@use
                    }
                }
                if (success) break
            } catch (e: Exception) {
                AppLogger.w(TAG, "Excepción descargando desde $url: ${e.message}")
                lastError = e
                if (destFile.exists()) destFile.delete()
            }
        }

        if (success && destFile.exists() && destFile.length() > 0) {
            _downloadProgress.update { it + (item.relativePath to 1.0f) }
            _downloadedFiles.update { it + (item.name to destFile.absolutePath) + (item.relativePath to destFile.absolutePath) }

            // Guardar también en la galería pública de Android (Movies o Pictures)
            saveToPublicGallery(destFile, item.isVideo)

            // Indexar en el MediaScanner del sistema
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf(if (item.isVideo) "video/mp4" else "image/jpeg"),
                null
            )

            withContext(Dispatchers.Main) { onFinished(destFile) }
        } else {
            AppLogger.e(TAG, "No se pudo descargar ${item.name} de ninguna de las URLs candidatas.", lastError)
            _downloadProgress.update { it - item.relativePath }
            if (destFile.exists()) destFile.delete()
            withContext(Dispatchers.Main) { onFinished(null) }
        }
    }

    fun saveToPublicGallery(file: File, isVideo: Boolean): android.net.Uri? {
        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/SJCAM" else "Pictures/SJCAM")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = if (isVideo) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
            }

            val uri = resolver.insert(collection, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { inp ->
                        inp.copyTo(out)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                AppLogger.i(TAG, "Copia registrada en MediaStore público: $uri")
                return uri
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Aviso registrando en MediaStore: ${e.message}")
        }
        return null
    }

    fun deleteLocalFile(item: CameraMediaItem) {
        val map = _downloadedFiles.value.toMutableMap()
        val localPath = map[item.name] ?: map[item.relativePath]
        if (localPath != null) {
            try {
                val f = File(localPath)
                if (f.exists()) {
                    f.delete()
                    AppLogger.i(TAG, "Archivo local borrado: $localPath")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error borrando archivo local $localPath: ${e.message}")
            }
        }
        _downloadedFiles.update { it - item.name - item.relativePath }
        _downloadProgress.update { it - item.relativePath }
    }
}
