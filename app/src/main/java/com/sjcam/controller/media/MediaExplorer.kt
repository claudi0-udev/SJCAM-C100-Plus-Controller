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
            var cmd3015Result = apiClient.executeCommand(3015)
            cmd3015Result.onSuccess { resp ->
                val xml = resp.rawXml
                AppLogger.i(TAG, "Respuesta cmd=3015:\n${xml.take(500)}")
                val parsed = parseNovatekXml(xml, cameraBaseUrl)
                if (parsed.isNotEmpty()) {
                    AppLogger.i(TAG, "cmd=3015 devolvió ${parsed.size} archivos.")
                    parsed.forEach { foundItems[it.relativePath] = it }
                }
            }

            // Si vino vacío, intentar con parámetro 1 o 0
            if (foundItems.isEmpty()) {
                val cmdWithPar = apiClient.executeCommand(3015, "1")
                cmdWithPar.onSuccess { resp ->
                    val xml = resp.rawXml
                    if (xml.isNotBlank()) {
                        AppLogger.i(TAG, "Respuesta cmd=3015&par=1:\n${xml.take(500)}")
                        val parsed = parseNovatekXml(xml, cameraBaseUrl)
                        parsed.forEach { foundItems[it.relativePath] = it }
                    }
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

    /**
     * Limpia y normaliza cualquier ruta devuelta por Novatek (DOS A:\DCIM\..., contrabarras, etc.)
     * Devuelve Pair(cleanRelativePath, fileName).
     */
    fun normalizeNovatekPath(rawPath: String): Pair<String, String> {
        var clean = rawPath.trim().replace('\\', '/')
        clean = clean.replaceFirst(Regex("^[a-zA-Z]:"), "")
        while (clean.contains("//")) {
            clean = clean.replace("//", "/")
        }
        val fileName = clean.substringAfterLast("/")
        if (!clean.contains("/") || clean == "/$fileName" || clean == fileName) {
            val isVid = isVideoFile(fileName)
            clean = if (isVid) "/DCIM/MOVIE/$fileName" else "/DCIM/PHOTO/$fileName"
        }
        if (!clean.startsWith("/")) {
            clean = "/$clean"
        }
        return Pair(clean, fileName)
    }

    private fun parseNovatekXml(xml: String, baseUrl: String): List<CameraMediaItem> {
        val list = mutableListOf<CameraMediaItem>()

        // 1. Buscar bloques <File ...>...</File> o <File ... />
        val fileBlockRegex = Pattern.compile("<File(?:\\s+[^>]*)?>(.*?)</File>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val matcher = fileBlockRegex.matcher(xml)

        while (matcher.find()) {
            val block = matcher.group(1) ?: continue
            val nameRegex = Pattern.compile("<(?:Name|Fpath|Path)>(.*?)</(?:Name|Fpath|Path)>", Pattern.CASE_INSENSITIVE)
            val sizeRegex = Pattern.compile("<(?:Size|Length)>(\\d+)</(?:Size|Length)>", Pattern.CASE_INSENSITIVE)
            val timeRegex = Pattern.compile("<(?:Time|Date)>(.*?)</(?:Time|Date)>", Pattern.CASE_INSENSITIVE)

            val mName = nameRegex.matcher(block)
            if (mName.find()) {
                val rawPath = mName.group(1)?.trim() ?: ""
                val (cleanPath, fileName) = normalizeNovatekPath(rawPath)
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
                            dateTimeStr = time,
                            rawNovatekPath = rawPath
                        )
                    )
                }
            }
        }

        // 2. Si no había bloques <File>, buscar etiquetas con atributos <File NAME="..." SIZE="..." />
        if (list.isEmpty()) {
            val fileAttrRegex = Pattern.compile("<File\\s+[^>]*NAME=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE)
            val mAttr = fileAttrRegex.matcher(xml)
            while (mAttr.find()) {
                val rawPath = mAttr.group(1)?.trim() ?: continue
                val (cleanPath, fileName) = normalizeNovatekPath(rawPath)
                val isVideo = isVideoFile(fileName)
                val isPhoto = isPhotoFile(fileName)
                if (isVideo || isPhoto) {
                    list.add(
                        CameraMediaItem(
                            name = fileName,
                            relativePath = cleanPath,
                            httpUrl = "$baseUrl$cleanPath",
                            isVideo = isVideo,
                            rawNovatekPath = rawPath
                        )
                    )
                }
            }
        }

        // 3. Si sigue vacío, buscar etiquetas <Name> o <Fpath> sueltas
        if (list.isEmpty()) {
            val looseRegex = Pattern.compile("<(?:Name|Fpath|Path)>(.*?(?:\\.(?:mp4|mov|jpg|jpeg)))</(?:Name|Fpath|Path)>", Pattern.CASE_INSENSITIVE)
            val mLoose = looseRegex.matcher(xml)
            while (mLoose.find()) {
                val rawPath = mLoose.group(1)?.trim() ?: continue
                val (cleanPath, fileName) = normalizeNovatekPath(rawPath)
                val isVideo = isVideoFile(fileName)
                val isPhoto = isPhotoFile(fileName)
                if (isVideo || isPhoto) {
                    list.add(
                        CameraMediaItem(
                            name = fileName,
                            relativePath = cleanPath,
                            httpUrl = "$baseUrl$cleanPath",
                            isVideo = isVideo,
                            rawNovatekPath = rawPath
                        )
                    )
                }
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
            val (cleanPath, fileName) = normalizeNovatekPath(fullRelPath)

            list.add(
                CameraMediaItem(
                    name = fileName,
                    relativePath = cleanPath,
                    httpUrl = "$baseUrl$cleanPath",
                    isVideo = isVideoFile(fileName),
                    rawNovatekPath = fullRelPath
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
