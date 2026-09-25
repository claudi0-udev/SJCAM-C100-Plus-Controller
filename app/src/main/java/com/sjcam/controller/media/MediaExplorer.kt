package com.sjcam.controller.media

import com.sjcam.controller.data.AppLogger
import com.sjcam.controller.data.CameraMediaItem
import com.sjcam.controller.network.SjcamApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class MediaExplorer(
    private val apiClient: SjcamApiClient,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    private val TAG = "MediaExplorer"

    // Rutas habituales de almacenamiento en cámaras Novatek SJCAM
    private val CANDIDATE_DIRS = listOf(
        "/DCIM/MOVIE",
        "/DCIM/PHOTO",
        "/DCIM/100MEDIA",
        "/DCIM/200MEDIA",
        "/DCIM"
    )

    suspend fun scanAllMedia(cameraBaseUrl: String): List<CameraMediaItem> = withContext(Dispatchers.IO) {
        val foundItems = mutableMapOf<String, CameraMediaItem>() // Evitar duplicados por ruta relativa

        AppLogger.i(TAG, "Iniciando escaneo de archivos en la MicroSD...")

        // Método 1: Intentar mediante comando Novatek cmd=3015
        try {
            val cmd3015Result = apiClient.executeCommand(3015)
            cmd3015Result.onSuccess { resp ->
                val xml = resp.rawXml
                AppLogger.d(TAG, "Respuesta cmd=3015:\n${xml.take(300)}")
                val parsed = parseNovatekXml(xml, cameraBaseUrl)
                if (parsed.isNotEmpty()) {
                    AppLogger.i(TAG, "cmd=3015 devolvió ${parsed.size} archivos.")
                    parsed.forEach { foundItems[it.relativePath] = it }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Aviso: Falló escaneo con cmd=3015: ${e.message}")
        }

        // Método 2: Exploración de directorios web HTTP de la cámara
        for (dir in CANDIDATE_DIRS) {
            try {
                val dirUrl = "$cameraBaseUrl$dir/"
                val request = Request.Builder().url(dirUrl).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val items = parseHtmlDirectoryListing(body, dir, cameraBaseUrl)
                        if (items.isNotEmpty()) {
                            AppLogger.i(TAG, "Directorio $dir: Encontrados ${items.size} archivos.")
                            items.forEach { foundItems[it.relativePath] = it }
                        }
                    }
                }
            } catch (e: Exception) {
                // Silencioso para directorios que no existan
            }
        }

        val resultList = foundItems.values.sortedByDescending { it.name }
        AppLogger.i(TAG, "Escaneo finalizado. Total de archivos multimedia encontrados: ${resultList.size}")
        resultList
    }

    private fun parseNovatekXml(xml: String, baseUrl: String): List<CameraMediaItem> {
        val list = mutableListOf<CameraMediaItem>()

        // 1. Buscar etiquetas <File>...</File> o <Name>...</Name>
        val fileBlockRegex = Pattern.compile("<File>(.*?)</File>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val matcher = fileBlockRegex.matcher(xml)

        while (matcher.find()) {
            val block = matcher.group(1) ?: continue
            val nameRegex = Pattern.compile("<(?:Name|Fpath|Path)>(.*?)</(?:Name|Fpath|Path)>", Pattern.CASE_INSENSITIVE)
            val sizeRegex = Pattern.compile("<Size>(\\d+)</Size>", Pattern.CASE_INSENSITIVE)
            val timeRegex = Pattern.compile("<(?:Time|Date)>(.*?)</(?:Time|Date)>", Pattern.CASE_INSENSITIVE)

            val mName = nameRegex.matcher(block)
            if (mName.find()) {
                val fullPath = mName.group(1)?.trim() ?: ""
                val cleanPath = if (fullPath.startsWith("/")) fullPath else "/$fullPath"
                val fileName = cleanPath.substringAfterLast("/")
                val mSize = sizeRegex.matcher(block)
                val size = if (mSize.find()) mSize.group(1)?.toLongOrNull() ?: 0 else 0
                val mTime = timeRegex.matcher(block)
                val time = if (mTime.find()) mTime.group(1)?.trim() ?: "" else ""

                val isVideo = isVideoFile(fileName)
                val isPhoto = isPhotoFile(fileName)

                if (isVideo || isPhoto) {
                    list.add(
                        CameraMediaItem(
                            name = fileName,
                            relativePath = cleanPath,
                            httpUrl = "$baseUrl$cleanPath",
                            isVideo = isVideo,
                            sizeBytes = size,
                            dateTimeStr = time
                        )
                    )
                }
            }
        }

        // 2. Si no había bloques <File>, buscar etiquetas <Name> sueltas
        if (list.isEmpty()) {
            val nameOnlyRegex = Pattern.compile("<(?:Name|String)>(.*?(?:\\.(?:mp4|mov|jpg|jpeg)))</(?:Name|String)>", Pattern.CASE_INSENSITIVE)
            val mNames = nameOnlyRegex.matcher(xml)
            while (mNames.find()) {
                val pathStr = mNames.group(1)?.trim() ?: continue
                val cleanPath = if (pathStr.startsWith("/")) pathStr else "/DCIM/$pathStr"
                val fileName = cleanPath.substringAfterLast("/")
                list.add(
                    CameraMediaItem(
                        name = fileName,
                        relativePath = cleanPath,
                        httpUrl = "$baseUrl$cleanPath",
                        isVideo = isVideoFile(fileName)
                    )
                )
            }
        }

        return list
    }

    private fun parseHtmlDirectoryListing(html: String, baseDir: String, baseUrl: String): List<CameraMediaItem> {
        val list = mutableListOf<CameraMediaItem>()
        // Capturar enlaces href="archivo.ext"
        val hrefRegex = Pattern.compile("href=[\"']([^\"']+\\.(?:mp4|MP4|mov|MOV|jpg|JPG|jpeg|JPEG))[\"']", Pattern.CASE_INSENSITIVE)
        val matcher = hrefRegex.matcher(html)

        while (matcher.find()) {
            val href = matcher.group(1)?.trim() ?: continue
            val fullRelPath = when {
                href.startsWith("/") -> href
                href.startsWith("http") -> href.substringAfter(baseUrl, href)
                else -> "${baseDir.trimEnd('/')}/$href"
            }
            val fileName = fullRelPath.substringAfterLast("/")

            list.add(
                CameraMediaItem(
                    name = fileName,
                    relativePath = fullRelPath,
                    httpUrl = "$baseUrl$fullRelPath",
                    isVideo = isVideoFile(fileName)
                )
            )
        }

        return list
    }

    private fun isVideoFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mov")
    }

    private fun isPhotoFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
    }
}
