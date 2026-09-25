package com.sjcam.controller.data

data class CameraStatus(
    val isConnected: Boolean = false,
    val isRecording: Boolean = false,
    val batteryLevel: Int? = null, // 0=Full, 1=80%, 2=60%, 3=40%, 4=20%, 5=Charging
    val freeSpaceKb: Long? = null,
    val currentMode: CameraMode = CameraMode.UNKNOWN,
    val isLiveStreaming: Boolean = false,
    val rtspUrl: String = "rtsp://192.168.1.254/sjcam.mov",
    val forceTcp: Boolean = true,
    val cameraIp: String = "192.168.1.254",
    val lastError: String? = null
) {
    fun batteryDisplay(): String {
        return when (batteryLevel) {
            0 -> "100% (Llena)"
            1 -> "75%"
            2 -> "50%"
            3 -> "25%"
            4 -> "Baja (<10%)"
            5 -> "Cargando ⚡"
            null -> "--"
            else -> "$batteryLevel/5"
        }
    }
}

enum class CameraMode(val code: Int) {
    UNKNOWN(-1),
    PHOTO(0),
    VIDEO(1),
    PLAYBACK(2)
}
