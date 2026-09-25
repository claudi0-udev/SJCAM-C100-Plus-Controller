package com.sjcam.controller.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    HTTP,
    RTSP
}

data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val payload: String? = null
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun toFormattedString(): String {
        val base = "[${formattedTime()}] [${level.name}] [$tag] $message"
        return if (!payload.isNullOrBlank()) {
            "$base\n  Payload:\n${payload.prependIndent("    ")}"
        } else {
            base
        }
    }
}
