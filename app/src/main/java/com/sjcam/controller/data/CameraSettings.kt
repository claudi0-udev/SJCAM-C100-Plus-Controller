package com.sjcam.controller.data

data class CameraSettings(
    val videoResolution: Int? = null,
    val photoResolution: Int? = null,
    val loopRecording: Int? = null,
    val audioEnabled: Boolean? = null,
    val dateStampEnabled: Boolean? = null,
    val wdrEnabled: Boolean? = null,
    val beepEnabled: Boolean? = null,
    val autoPowerOff: Int? = null,
    val whiteBalance: Int? = null,
    val evBias: Int? = null,
    val firmwareVersion: String? = null,
    val totalSpaceMb: Long? = null,
    val freeSpaceMb: Long? = null,
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val allReadSettings: List<SettingItem> = emptyList()
) {
    companion object {
        val VIDEO_RESOLUTIONS = listOf(
            0 to "2K (2560x1440) 30fps",
            1 to "1080P (1920x1080) 60fps",
            2 to "1080P (1920x1080) 30fps",
            3 to "720P (1280x720) 120fps",
            4 to "720P (1280x720) 60fps"
        )

        val PHOTO_RESOLUTIONS = listOf(
            0 to "15 MP",
            1 to "12 MP",
            2 to "10 MP",
            3 to "8 MP",
            4 to "5 MP",
            5 to "3 MP"
        )

        val LOOP_RECORDING_OPTIONS = listOf(
            0 to "Desactivado",
            1 to "1 Minuto",
            2 to "3 Minutos",
            3 to "5 Minutos"
        )

        val AUTO_POWER_OFF_OPTIONS = listOf(
            0 to "Desactivado (Nunca)",
            1 to "1 Minuto",
            2 to "3 Minutos",
            3 to "5 Minutos"
        )

        val WHITE_BALANCE_OPTIONS = listOf(
            0 to "Automático",
            1 to "Luz de Día",
            2 to "Nublado",
            3 to "Tungsteno",
            4 to "Fluorescente"
        )

        val EV_BIAS_OPTIONS = listOf(
            0 to "+2.0 EV",
            1 to "+5/3 EV",
            2 to "+4/3 EV",
            3 to "+1.0 EV",
            4 to "+2/3 EV",
            5 to "+1/3 EV",
            6 to "0.0 EV (Normal)",
            7 to "-1/3 EV",
            8 to "-2/3 EV",
            9 to "-1.0 EV",
            10 to "-4/3 EV",
            11 to "-5/3 EV",
            12 to "-2.0 EV"
        )
    }
}

data class SettingItem(
    val cmd: Int,
    val name: String,
    val value: String,
    val readableText: String
)
