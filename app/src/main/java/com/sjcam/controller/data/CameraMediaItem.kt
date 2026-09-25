package com.sjcam.controller.data

import java.net.URLEncoder
import java.util.Locale

data class CameraMediaItem(
    val name: String,
    val relativePath: String,
    val httpUrl: String,
    val isVideo: Boolean,
    val sizeBytes: Long = 0,
    val dateTimeStr: String = "",
    val rawNovatekPath: String = "",
    val isDownloaded: Boolean = false,
    val localFilePath: String? = null
) {
    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return "--"
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                else -> String.format(Locale.US, "%.0f KB", kb)
            }
        }

    /**
     * Devuelve una lista ordenada de URLs candidatas para descargar o reproducir el archivo
     * desde la cámara Novatek, probando tanto la ruta HTTP directa como los comandos CGI Novatek.
     */
    fun getCandidateUrls(baseUrl: String): List<String> {
        val urls = LinkedHashSet<String>()
        val base = baseUrl.trimEnd('/')

        // 1. Ruta HTTP directa limpia (/DCIM/MOVIE/...)
        val cleanRel = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
        urls.add("$base$cleanRel")

        // 2. Ruta directa sin barra inicial
        urls.add("$base/${cleanRel.trimStart('/')}")

        // 3. CGI Novatek cmd=4001 con la ruta raw original (ej: A:\DCIM\MOVIE\xxx.MP4)
        if (rawNovatekPath.isNotBlank()) {
            try {
                val encRaw = URLEncoder.encode(rawNovatekPath, "UTF-8")
                urls.add("$base/?custom=1&cmd=4001&str=$encRaw")
            } catch (_: Exception) {}

            try {
                val encForward = URLEncoder.encode(rawNovatekPath.replace('\\', '/'), "UTF-8")
                urls.add("$base/?custom=1&cmd=4001&str=$encForward")
            } catch (_: Exception) {}
        }

        // 4. CGI Novatek cmd=4001 con ruta relativa limpia
        try {
            val encClean = URLEncoder.encode(cleanRel.trimStart('/'), "UTF-8")
            urls.add("$base/?custom=1&cmd=4001&str=$encClean")
        } catch (_: Exception) {}

        // 5. CGI Novatek con solo el nombre de archivo
        try {
            val encName = URLEncoder.encode(name, "UTF-8")
            urls.add("$base/?custom=1&cmd=4001&str=$encName")
        } catch (_: Exception) {}

        return urls.toList()
    }
}

enum class MediaFilter(val label: String) {
    ALL("Todos"),
    VIDEOS("Videos"),
    PHOTOS("Fotos")
}

