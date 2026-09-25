package com.sjcam.controller.data

import java.util.Locale

data class CameraMediaItem(
    val name: String,
    val relativePath: String,
    val httpUrl: String,
    val isVideo: Boolean,
    val sizeBytes: Long = 0,
    val dateTimeStr: String = "",
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
}

enum class MediaFilter(val label: String) {
    ALL("Todos"),
    VIDEOS("Videos"),
    PHOTOS("Fotos")
}

