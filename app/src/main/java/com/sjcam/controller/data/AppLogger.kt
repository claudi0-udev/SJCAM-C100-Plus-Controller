package com.sjcam.controller.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {
    private const val MAX_LOGS = 1000
    private val logList = CopyOnWriteArrayList<LogEntry>()
    private val _logsState = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsState: StateFlow<List<LogEntry>> = _logsState.asStateFlow()

    @Synchronized
    fun log(level: LogLevel, tag: String, message: String, payload: String? = null) {
        val entry = LogEntry(
            level = level,
            tag = tag,
            message = message,
            payload = payload
        )

        // Logcat espejo
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, "$message ${payload ?: ""}")
            LogLevel.HTTP -> Log.d("HTTP-$tag", "$message ${payload ?: ""}")
            LogLevel.RTSP -> Log.d("RTSP-$tag", message)
        }

        if (logList.size >= MAX_LOGS) {
            logList.removeAt(0)
        }
        logList.add(entry)
        _logsState.value = ArrayList(logList)
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val payload = throwable?.stackTraceToString()
        log(LogLevel.ERROR, tag, message, payload)
    }
    fun http(tag: String, message: String, payload: String? = null) =
        log(LogLevel.HTTP, tag, message, payload)
    fun rtsp(tag: String, message: String) = log(LogLevel.RTSP, tag, message)

    fun clear() {
        logList.clear()
        _logsState.value = emptyList()
    }

    fun exportFormattedLogs(): String {
        val sb = StringBuilder()
        sb.append("=== SJCAM C100+ APP DEBUG LOGS ===\n")
        sb.append("Generated: ${java.util.Date()}\n")
        sb.append("Total entries: ${logList.size}\n")
        sb.append("===================================\n\n")
        for (entry in logList) {
            sb.append(entry.toFormattedString()).append("\n")
        }
        return sb.toString()
    }
}
