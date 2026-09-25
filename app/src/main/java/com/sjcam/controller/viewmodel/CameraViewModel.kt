package com.sjcam.controller.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sjcam.controller.data.AppLogger
import com.sjcam.controller.data.CameraMediaItem
import com.sjcam.controller.data.CameraMode
import com.sjcam.controller.data.CameraStatus
import com.sjcam.controller.data.MediaFilter
import com.sjcam.controller.network.CameraNetworkManager
import com.sjcam.controller.network.SjcamApiClient
import com.sjcam.controller.player.VlcPlayerManager
import com.sjcam.controller.service.CameraRecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "CameraViewModel"

    val networkManager = CameraNetworkManager(application)
    val apiClient = SjcamApiClient("http://192.168.1.254")
    val playerManager = VlcPlayerManager(application)
    val updateManager = com.sjcam.controller.updater.AppUpdateManager(application)
    val updateState = updateManager.updateState

    private val _cameraStatus = MutableStateFlow(CameraStatus())
    val cameraStatus: StateFlow<CameraStatus> = _cameraStatus.asStateFlow()

    private val _cameraSettings = MutableStateFlow(com.sjcam.controller.data.CameraSettings())
    val cameraSettings: StateFlow<com.sjcam.controller.data.CameraSettings> = _cameraSettings.asStateFlow()

    private val _rawCommandResult = MutableStateFlow<String>("")
    val rawCommandResult: StateFlow<String> = _rawCommandResult.asStateFlow()

    // === GRABACIÓN DIRECTA AL CELULAR (MODO BOLSILLO / SIN MICROSD) ===
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    val isPhoneRecording = playerManager.isPhoneRecording
    private val _phoneRecordingSeconds = MutableStateFlow(0)
    val phoneRecordingSeconds: StateFlow<Int> = _phoneRecordingSeconds.asStateFlow()
    private val _phoneRecordMessage = MutableStateFlow<String?>(null)
    val phoneRecordMessage: StateFlow<String?> = _phoneRecordMessage.asStateFlow()
    private var phoneRecordTimerJob: Job? = null

    init {
        AppLogger.i(TAG, "CameraViewModel inicializado.")
        networkManager.bindProcessToWifiNetwork()
        // Iniciar monitor de estado de cámara y botón físico
        startHeartbeat()
        // Buscar actualizaciones silenciosamente al inicio
        checkForAppUpdates()

        // Callback cuando LibVLC finaliza la escritura de un clip local en disco
        playerManager.onRecordFinished = { recordedFile ->
            viewModelScope.launch(Dispatchers.IO) {
                val fileLength = recordedFile.length()
                AppLogger.i(TAG, "Clip en celular finalizado: ${recordedFile.absolutePath} ($fileLength bytes)")
                if (fileLength > 0) {
                    val uri = downloadManager.saveToPublicGallery(recordedFile, isVideo = true)
                    val sizeMb = String.format(java.util.Locale.US, "%.1f", fileLength / (1024.0 * 1024.0))
                    withContext(Dispatchers.Main) {
                        if (uri != null) {
                            _phoneRecordMessage.value = "¡Video guardado en Movies/SJCAM! (${recordedFile.name} - ${sizeMb} MB)"
                            AppLogger.i(TAG, "Video guardado con éxito en galería pública Movies/SJCAM: $uri")
                        } else {
                            _phoneRecordMessage.value = "¡Video guardado! (${recordedFile.name} - ${sizeMb} MB)"
                        }
                    }
                    android.media.MediaScannerConnection.scanFile(
                        getApplication(),
                        arrayOf(recordedFile.absolutePath),
                        arrayOf("video/mp4"),
                        null
                    )
                    downloadManager.checkExistingDownloads()
                    // Si se guardó en la galería pública (MediaStore), eliminar el archivo temporal
                    // para no duplicar espacio en el almacenamiento interno del teléfono
                    if (uri != null) {
                        try {
                            recordedFile.delete()
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "No se pudo borrar temporal local: ${e.message}")
                        }
                    }
                }
                CameraRecordingService.stop(getApplication())
                releaseWakeLock()
            }
        }
    }

    fun checkForAppUpdates() {
        viewModelScope.launch {
            updateManager.checkForUpdates()
        }
    }

    fun downloadAppUpdate(release: com.sjcam.controller.updater.ReleaseInfo) {
        viewModelScope.launch {
            updateManager.downloadAndPrepareInstall(release)
        }
    }

    fun installAppUpdate(apkFile: java.io.File) {
        updateManager.promptInstall(apkFile)
    }

    fun dismissAppUpdate() {
        updateManager.resetState()
    }

    // === GESTIÓN DE GALERÍA Y ARCHIVOS MICROSD ===
    val mediaExplorer = com.sjcam.controller.media.MediaExplorer(apiClient)
    val downloadManager = com.sjcam.controller.media.MediaDownloadManager(application)

    private val _mediaItems = MutableStateFlow<List<com.sjcam.controller.data.CameraMediaItem>>(emptyList())
    val mediaItems: StateFlow<List<com.sjcam.controller.data.CameraMediaItem>> = _mediaItems.asStateFlow()

    private val _isScanningMedia = MutableStateFlow(false)
    val isScanningMedia: StateFlow<Boolean> = _isScanningMedia.asStateFlow()

    private val _mediaFilter = MutableStateFlow(MediaFilter.ALL)
    val mediaFilter: StateFlow<MediaFilter> = _mediaFilter.asStateFlow()

    private val _selectedMediaToPlay = MutableStateFlow<com.sjcam.controller.data.CameraMediaItem?>(null)
    val selectedMediaToPlay: StateFlow<com.sjcam.controller.data.CameraMediaItem?> = _selectedMediaToPlay.asStateFlow()

    private val _selectedPhotoToView = MutableStateFlow<com.sjcam.controller.data.CameraMediaItem?>(null)
    val selectedPhotoToView: StateFlow<com.sjcam.controller.data.CameraMediaItem?> = _selectedPhotoToView.asStateFlow()

    val downloadProgress = downloadManager.downloadProgress
    val downloadedFiles = downloadManager.downloadedFiles

    fun setMediaFilter(filter: MediaFilter) {
        _mediaFilter.value = filter
    }

    fun refreshMediaList() {
        viewModelScope.launch {
            _isScanningMedia.value = true
            try {
                val list = mediaExplorer.scanAllMedia(apiClient.cameraBaseUrl)
                _mediaItems.value = list
                downloadManager.checkExistingDownloads()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error escaneando medios: ${e.message}")
            } finally {
                _isScanningMedia.value = false
            }
        }
    }

    fun downloadMedia(item: com.sjcam.controller.data.CameraMediaItem) {
        viewModelScope.launch {
            downloadManager.downloadFile(item, apiClient.cameraBaseUrl)
        }
    }

    fun playVideo(item: com.sjcam.controller.data.CameraMediaItem) {
        _selectedMediaToPlay.value = item
    }

    fun viewPhoto(item: com.sjcam.controller.data.CameraMediaItem) {
        _selectedPhotoToView.value = item
    }

    fun closeMediaViewer() {
        _selectedMediaToPlay.value = null
        _selectedPhotoToView.value = null
    }

    // === GESTIÓN DE BORRADO DE ARCHIVOS MICROSD / LOCAL ===
    suspend fun deleteMediaItem(item: com.sjcam.controller.data.CameraMediaItem): Boolean {
        return withContext(Dispatchers.IO) {
            AppLogger.i(TAG, "Eliminando archivo: ${item.name} (raw: ${item.rawNovatekPath}, rel: ${item.relativePath})")
            val targetPath = item.rawNovatekPath.ifBlank { item.relativePath }
            val result = apiClient.deleteFile(targetPath, item.relativePath)

            // Borrar copia local si fue descargada
            downloadManager.deleteLocalFile(item)

            // Actualizar lista en pantalla quitando el elemento
            _mediaItems.update { current ->
                current.filter { it.relativePath != item.relativePath && it.name != item.name }
            }

            // Si el visor modal estaba mostrando este archivo, cerrarlo
            if (_selectedMediaToPlay.value?.relativePath == item.relativePath) {
                _selectedMediaToPlay.value = null
            }
            if (_selectedPhotoToView.value?.relativePath == item.relativePath) {
                _selectedPhotoToView.value = null
            }

            val isOk = result.isSuccess && (result.getOrNull()?.isSuccess == true)
            if (isOk) {
                AppLogger.i(TAG, "¡Archivo ${item.name} eliminado exitosamente de la MicroSD!")
            } else {
                AppLogger.w(TAG, "Aviso de cámara al intentar borrar ${item.name}: ${result.getOrNull()?.rawXml}")
            }
            isOk
        }
    }

    suspend fun deleteMultipleMediaItems(items: List<com.sjcam.controller.data.CameraMediaItem>): Pair<Int, Int> {
        return withContext(Dispatchers.IO) {
            var successCount = 0
            var failCount = 0
            for (item in items) {
                val ok = deleteMediaItem(item)
                if (ok) successCount++ else failCount++
                delay(120) // Pequeña pausa para no saturar el servidor HTTP embebido del DSP Novatek
            }
            AppLogger.i(TAG, "Eliminación por selección completada: $successCount exitosos, $failCount fallidos.")
            Pair(successCount, failCount)
        }
    }

    // === MÉTODOS DE GRABACIÓN DIRECTA AL CELULAR ===
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getApplication<Application>().getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "com.sjcam.controller:PhoneRecordingWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(45 * 60 * 1000L /* 45 minutos máx de seguridad */)
                AppLogger.i(TAG, "WakeLock adquirido: Grabación continuará en el bolsillo con pantalla apagada.")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Aviso WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLogger.i(TAG, "WakeLock liberado.")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error liberando WakeLock: ${e.message}")
        }
    }

    fun togglePhoneRecording() {
        viewModelScope.launch {
            if (playerManager.isPhoneRecording.value) {
                AppLogger.i(TAG, "Deteniendo grabación directa en el celular...")
                playerManager.stopPhoneRecording()
                phoneRecordTimerJob?.cancel()
                phoneRecordTimerJob = null
                CameraRecordingService.stop(getApplication())
                releaseWakeLock()
            } else {
                AppLogger.i(TAG, "Iniciando grabación directa en el celular...")
                // Asegurar que el stream RTSP esté activo
                if (!_cameraStatus.value.isLiveStreaming || !playerManager.isPlaying.value) {
                    apiClient.setCameraMode(CameraMode.VIDEO)
                    delay(200)
                    apiClient.enableLiveStream()
                    delay(300)
                    _cameraStatus.update { it.copy(isLiveStreaming = true) }
                    val currentStatus = _cameraStatus.value
                    playerManager.startStream(currentStatus.rtspUrl, currentStatus.forceTcp)
                    delay(600)
                }

                val moviesDir = java.io.File(
                    getApplication<Application>().getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
                    "SJCAM_Local"
                ).apply { mkdirs() }

                // Iniciar Foreground Service para blindar Wi-Fi y mantener CPU activa en bolsillo
                CameraRecordingService.start(getApplication())
                acquireWakeLock()

                val started = playerManager.startPhoneRecording(moviesDir)
                if (started) {
                    _phoneRecordingSeconds.value = 0
                    phoneRecordTimerJob?.cancel()
                    phoneRecordTimerJob = viewModelScope.launch {
                        while (isActive && playerManager.isPhoneRecording.value) {
                            delay(1000)
                            _phoneRecordingSeconds.value++
                        }
                    }
                    _phoneRecordMessage.value = "Grabando directo en el teléfono (Movies/SJCAM)..."
                } else {
                    CameraRecordingService.stop(getApplication())
                    releaseWakeLock()
                    _phoneRecordMessage.value = "Error: no se pudo iniciar la grabación en el teléfono"
                }
            }
        }
    }

    fun clearPhoneRecordMessage() {
        _phoneRecordMessage.value = null
    }

    fun updateCameraIp(ip: String) {
        val cleanIp = ip.trim()
        val baseUrl = if (cleanIp.startsWith("http://")) cleanIp else "http://$cleanIp"
        apiClient.cameraBaseUrl = baseUrl
        _cameraStatus.update {
            it.copy(
                cameraIp = cleanIp,
                rtspUrl = "rtsp://$cleanIp/sjcam.mov"
            )
        }
        AppLogger.i(TAG, "IP de la cámara actualizada a: $baseUrl")
    }

    fun updateRtspUrl(url: String) {
        _cameraStatus.update { it.copy(rtspUrl = url.trim()) }
        AppLogger.i(TAG, "URL RTSP actualizada a: ${url.trim()}")
    }

    fun setForceTcp(forceTcp: Boolean) {
        _cameraStatus.update { it.copy(forceTcp = forceTcp) }
        AppLogger.i(TAG, "Forzar RTP sobre TCP cambiado a: $forceTcp")
    }

    fun checkConnectionAndBattery() {
        viewModelScope.launch {
            AppLogger.i(TAG, "Comprobando conexión y estado con la cámara...")
            val result = apiClient.getBattery()
            result.onSuccess { response ->
                val batteryVal = response.value?.toIntOrNull()
                _cameraStatus.update {
                    it.copy(
                        isConnected = true,
                        batteryLevel = batteryVal,
                        lastError = null
                    )
                }
                AppLogger.i(TAG, "Cámara conectada! Batería nivel: $batteryVal (${_cameraStatus.value.batteryDisplay()})")
            }.onFailure { err ->
                _cameraStatus.update {
                    it.copy(
                        isConnected = false,
                        lastError = "Fallo de conexión: ${err.message}"
                    )
                }
                AppLogger.e(TAG, "No se pudo conectar a la cámara en ${apiClient.cameraBaseUrl}", err)
            }
        }
    }

    fun toggleRecording() {
        viewModelScope.launch {
            val currentlyRecording = _cameraStatus.value.isRecording
            val targetState = !currentlyRecording
            val wasLiveStreaming = _cameraStatus.value.isLiveStreaming
            AppLogger.i(TAG, "Solicitando cambio de grabación: ${if (targetState) "INICIAR" else "DETENER"} (liveStreamActivo=$wasLiveStreaming)")

            if (targetState) {
                // Solo si no estamos ya en streaming (el streaming ya garantiza modo video)
                if (!wasLiveStreaming) {
                    apiClient.setCameraMode(CameraMode.VIDEO)
                    delay(200)
                }
            }

            val result = if (targetState) apiClient.startRecording() else apiClient.stopRecording()
            result.onSuccess { resp ->
                if (resp.isSuccess) {
                    _cameraStatus.update { it.copy(isRecording = targetState, lastError = null) }
                    AppLogger.i(TAG, "Grabación ${if (targetState) "INICIADA" else "DETENIDA"} exitosamente.")

                    // Cuando el chip Novatek empieza o termina de escribir en el archivo MP4 de la SD,
                    // reinicia la sesión RTSP de LIVE555. Reanudamos el stream automáticamente:
                    if (wasLiveStreaming) {
                        AppLogger.i(TAG, "Re-sincronizando previsualización RTSP tras cambio de grabación...")
                        delay(700)
                        apiClient.enableLiveStream()
                        delay(350)
                        val currentStatus = _cameraStatus.value
                        playerManager.startStream(currentStatus.rtspUrl, currentStatus.forceTcp)
                    }
                } else {
                    val err = "Error de cámara al cambiar grabación: Status=${resp.status}"
                    _cameraStatus.update { it.copy(lastError = err) }
                    AppLogger.w(TAG, err)
                }
            }.onFailure { err ->
                _cameraStatus.update { it.copy(lastError = "Error: ${err.message}") }
            }
        }
    }

    fun takePhoto() {
        if (_cameraStatus.value.isRecording) {
            val warn = "No se puede capturar fotos mientras la cámara está grabando video."
            AppLogger.w(TAG, warn)
            _cameraStatus.update { it.copy(lastError = warn) }
            return
        }

        viewModelScope.launch {
            val wasStreaming = _cameraStatus.value.isLiveStreaming
            AppLogger.i(TAG, "Cambiando a modo Foto (cmd=3001&par=0) para captura...")
            val modeResult = apiClient.setCameraMode(CameraMode.PHOTO)
            modeResult.onSuccess {
                AppLogger.i(TAG, "Modo foto establecido (Status: ${it.status}). Disparando foto (cmd=1001)...")
            }
            delay(300)

            val result = apiClient.takePhoto()
            result.onSuccess { resp ->
                if (resp.isSuccess) {
                    AppLogger.i(TAG, "¡Foto capturada exitosamente! Archivo: ${resp.value ?: "OK"}")
                    _cameraStatus.update { it.copy(lastError = null) }
                } else {
                    val err = "Disparo fallido (Status=${resp.status})"
                    AppLogger.w(TAG, err)
                    _cameraStatus.update { it.copy(lastError = err) }
                }
            }.onFailure { err ->
                _cameraStatus.update { it.copy(lastError = "Error al tomar foto: ${err.message}") }
            }

            // Si el stream estaba activo, restaurar el modo Video y reanudar el stream
            if (wasStreaming) {
                delay(500)
                AppLogger.i(TAG, "Restaurando modo Video y reactivando live stream tras captura...")
                apiClient.setCameraMode(CameraMode.VIDEO)
                delay(300)
                apiClient.enableLiveStream()
                delay(350)
                val currentStatus = _cameraStatus.value
                playerManager.startStream(currentStatus.rtspUrl, currentStatus.forceTcp)
            }
        }
    }

    fun toggleLiveStream() {
        viewModelScope.launch {
            val streaming = _cameraStatus.value.isLiveStreaming
            if (streaming) {
                AppLogger.i(TAG, "Deteniendo Live Stream...")
                stopHeartbeat()
                playerManager.stopStream()
                apiClient.disableLiveStream()
                _cameraStatus.update { it.copy(isLiveStreaming = false) }
            } else {
                // Solo enviar cmd=3001 si NO estamos grabando (para no interrumpir la grabación activa)
                if (!_cameraStatus.value.isRecording) {
                    AppLogger.i(TAG, "Iniciando Live Stream. Asegurando modo Video (cmd=3001&par=1)...")
                    apiClient.setCameraMode(CameraMode.VIDEO)
                    delay(300)
                }

                AppLogger.i(TAG, "Enviando cmd=2015&par=1...")
                val res = apiClient.enableLiveStream()
                res.onSuccess { resp ->
                    AppLogger.i(TAG, "Comando RTSP 2015 aceptado (Status: ${resp.status}). Esperando inicialización del DSP de la cámara...")
                }.onFailure { err ->
                    AppLogger.w(TAG, "Aviso: cmd=2015 devolvió error (${err.message}), intentando conectar RTSP de todos modos...")
                }
                delay(350)
                _cameraStatus.update { it.copy(isLiveStreaming = true) }
                val currentStatus = _cameraStatus.value
                playerManager.startStream(currentStatus.rtspUrl, currentStatus.forceTcp)
                startHeartbeat()
            }
        }
    }

    private var heartbeatJob: kotlinx.coroutines.Job? = null

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            AppLogger.i(TAG, "Monitor activo de cámara y botón físico iniciado (ping cada 2.5s)...")
            while (isActive) {
                delay(2500)
                try {
                    // 1. Telemetría de batería y conexión
                    val batt = apiClient.getBattery()
                    batt.onSuccess { resp ->
                        val batteryVal = resp.value?.toIntOrNull()
                        if (batteryVal != null) {
                            _cameraStatus.update { it.copy(isConnected = true, batteryLevel = batteryVal) }
                        }
                    }

                    // 2. Detección en tiempo real de botón físico de grabación (cmd=2001)
                    val recCheck = apiClient.executeCommand(2001)
                    recCheck.onSuccess { resp ->
                        val isCameraRecording = (resp.status == 1 || resp.value == "1" || resp.rawXml.contains("<Status>1</Status>"))
                        val wasRecording = _cameraStatus.value.isRecording

                        if (isCameraRecording != wasRecording) {
                            AppLogger.i(TAG, "¡Cambio de estado detectado desde botón físico de la cámara! Grabando: $isCameraRecording")
                            _cameraStatus.update { it.copy(isRecording = isCameraRecording) }

                            // Si se presionó el botón físico y la previsualización en vivo está activa,
                            // resincronizar LibVLC para que el video continúe sin interrupción
                            if (_cameraStatus.value.isLiveStreaming) {
                                delay(600)
                                val currentStatus = _cameraStatus.value
                                playerManager.startStream(currentStatus.rtspUrl, currentStatus.forceTcp)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Fallos de timeout si la cámara se apaga
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // === GESTIÓN WI-FI IN-APP ===
    private val _wifiStatusMessage = MutableStateFlow<String?>(null)
    val wifiStatusMessage: StateFlow<String?> = _wifiStatusMessage.asStateFlow()

    fun connectToCameraWifi(ssid: String, pass: String) {
        networkManager.connectDirectToWifi(ssid, pass) { msg ->
            _wifiStatusMessage.value = msg
        }
    }

    fun openSystemWifiPanel() {
        networkManager.openSystemWifiSettings()
    }

    fun clearWifiStatus() {
        _wifiStatusMessage.value = null
    }

    fun sendRawCommand(cmdStr: String, parStr: String) {
        val cmd = cmdStr.trim().toIntOrNull()
        if (cmd == null) {
            _rawCommandResult.value = "Error: cmd debe ser un número entero."
            return
        }
        val par = parStr.trim().ifBlank { null }

        viewModelScope.launch {
            AppLogger.i(TAG, "Enviando comando de depuración: cmd=$cmd, par=$par")
            val result = apiClient.executeCommand(cmd, par)
            result.onSuccess { resp ->
                _rawCommandResult.value = "Status: ${resp.status}\nCmd: ${resp.cmd}\nValue: ${resp.value}\n\nRaw XML:\n${resp.rawXml}"
            }.onFailure { err ->
                _rawCommandResult.value = "Error de petición: ${err.message}"
            }
        }
    }

    fun sendRawUrl(path: String) {
        if (path.isBlank()) return
        viewModelScope.launch {
            AppLogger.i(TAG, "Enviando URL raw: $path")
            val result = apiClient.executeRawUrl(path)
            result.onSuccess { body ->
                _rawCommandResult.value = "Respuesta:\n$body"
            }.onFailure { err ->
                _rawCommandResult.value = "Error de petición: ${err.message}"
            }
        }
    }

    fun runFullDiagnostics() {
        viewModelScope.launch {
            AppLogger.i(TAG, "=== INICIANDO AUTO-DIAGNÓSTICO COMPLETO ===")
            val ip = _cameraStatus.value.cameraIp

            // 1. Asegurar live view activo con cmd=2015&par=1
            AppLogger.i(TAG, "Paso 1: Activando live stream con cmd=2015&par=1...")
            apiClient.setCameraMode(CameraMode.VIDEO)
            delay(300)
            apiClient.enableLiveStream()
            delay(500)

            // 2. Escaneo de puertos
            AppLogger.i(TAG, "Paso 2: Verificando puertos de video...")
            val openPorts = com.sjcam.controller.network.CameraDiagnostics.runPortScan(ip)

            // 3. Probar rutas RTSP si el puerto 554 está abierto
            if (openPorts.contains(554)) {
                AppLogger.i(TAG, "Paso 3: Analizando respuestas SDP en puerto 554...")
                com.sjcam.controller.network.CameraDiagnostics.probeRtspPaths(ip)
            }

            // 4. Probar si existe test.html o información de firmware
            AppLogger.i(TAG, "Paso 4: Consultando versión de firmware...")
            val fwResult = apiClient.executeCommand(3012)
            fwResult.onSuccess { AppLogger.i(TAG, "Firmware (cmd=3012): ${it.value ?: it.rawXml}") }

            val testHtml = apiClient.executeRawUrl("/test.html")
            testHtml.onSuccess { AppLogger.i(TAG, "Página test.html encontrada:\n${it.take(250)}...") }

            AppLogger.i(TAG, "=== DIAGNÓSTICO FINALIZADO ===")
        }
    }

    fun loadAllSettings() {
        viewModelScope.launch {
            _cameraSettings.update { it.copy(isLoading = true, statusMessage = "Consultando configuración actual a la cámara...") }
            AppLogger.i(TAG, "Leyendo configuración completa de la SJCAM C100+...")
            val list = mutableListOf<com.sjcam.controller.data.SettingItem>()

            // 1. Firmware y Modelo (3012)
            apiClient.executeCommand(3012).onSuccess { resp ->
                val str = resp.value ?: resp.rawXml.replace(Regex("<.*?>"), " ").trim()
                _cameraSettings.update { it.copy(firmwareVersion = str) }
                list.add(com.sjcam.controller.data.SettingItem(3012, "Firmware y Modelo", resp.value ?: "OK", str))
            }

            // 2. Nivel de Batería (3019)
            apiClient.getBattery().onSuccess { resp ->
                val b = resp.value?.toIntOrNull()
                if (b != null) {
                    _cameraStatus.update { it.copy(isConnected = true, batteryLevel = b) }
                    list.add(com.sjcam.controller.data.SettingItem(3019, "Nivel de Batería", "$b", _cameraStatus.value.batteryDisplay()))
                }
            }

            // 3. Almacenamiento MicroSD (3017: <Total> y <Free> en bytes)
            apiClient.getFreeSpace().onSuccess { resp ->
                val totalBytes = resp.totalBytes
                val freeBytes = resp.freeBytes
                if (totalBytes != null && freeBytes != null) {
                    val totalMb = totalBytes / (1024 * 1024)
                    val freeMb = freeBytes / (1024 * 1024)
                    _cameraSettings.update {
                        it.copy(
                            totalSpaceMb = totalMb,
                            freeSpaceMb = freeMb
                        )
                    }
                    val totalGb = String.format(java.util.Locale.US, "%.1f", totalMb.toDouble() / 1024.0)
                    val freeGb = String.format(java.util.Locale.US, "%.1f", freeMb.toDouble() / 1024.0)
                    list.add(com.sjcam.controller.data.SettingItem(3017, "Espacio MicroSD", "${freeMb}MB / ${totalMb}MB", "$freeGb GB libres de $totalGb GB"))
                }
            }

            // 4. Volcado Maestro de Parámetros (3014): devuelve todos los pares <Cmd>X</Cmd><Status>Y</Status>
            apiClient.executeCommand(3014).onSuccess { resp ->
                val pairs = resp.settingsPairs
                AppLogger.i(TAG, "cmd=3014 devolvió ${pairs.size} parámetros actuales de la cámara: $pairs")

                val videoRes = pairs[2002]
                val loopRec = pairs[2003]
                val wdr = pairs[2004]?.let { it == 1 }
                val ev = pairs[2005]
                val audio = pairs[2007]?.let { it == 1 }
                val dateStamp = pairs[2008]?.let { it == 1 }
                val photoRes = pairs[1002]
                val wb = pairs[1005]
                val beep = pairs[3003]?.let { it == 1 }
                val autoOff = pairs[3008]

                _cameraSettings.update {
                    it.copy(
                        videoResolution = videoRes ?: it.videoResolution,
                        loopRecording = loopRec ?: it.loopRecording,
                        wdrEnabled = wdr ?: it.wdrEnabled,
                        evBias = ev ?: it.evBias,
                        audioEnabled = audio ?: it.audioEnabled,
                        dateStampEnabled = dateStamp ?: it.dateStampEnabled,
                        photoResolution = photoRes ?: it.photoResolution,
                        whiteBalance = wb ?: it.whiteBalance,
                        beepEnabled = beep ?: it.beepEnabled,
                        autoPowerOff = autoOff ?: it.autoPowerOff
                    )
                }

                // Generar lista legible con todos los parámetros leídos
                pairs.forEach { (cmd, status) ->
                    val (name, text) = describeSetting(cmd, status)
                    list.add(com.sjcam.controller.data.SettingItem(cmd, name, "$status", text))
                }
            }

            _cameraSettings.update {
                it.copy(
                    isLoading = false,
                    statusMessage = "Se leyeron ${list.size} parámetros en tiempo real de la cámara.",
                    allReadSettings = list
                )
            }
            AppLogger.i(TAG, "Configuración completa cargada con éxito (${list.size} parámetros).")
        }
    }

    private fun describeSetting(cmd: Int, status: Int): Pair<String, String> {
        return when (cmd) {
            2002 -> "Resolución de Video" to (com.sjcam.controller.data.CameraSettings.VIDEO_RESOLUTIONS.find { it.first == status }?.second ?: "Índice $status")
            2003 -> "Grabación en Bucle" to (com.sjcam.controller.data.CameraSettings.LOOP_RECORDING_OPTIONS.find { it.first == status }?.second ?: "Índice $status")
            2004 -> "WDR (Rango Dinámico)" to if (status == 1) "Activado" else "Desactivado"
            2005 -> "Compensación EV" to (com.sjcam.controller.data.CameraSettings.EV_BIAS_OPTIONS.find { it.first == status }?.second ?: "Valor $status")
            2006 -> "Detección de Movimiento" to if (status == 1) "Activado" else "Desactivado"
            2007 -> "Audio de Micrófono" to if (status == 1) "Activado" else "Silenciado"
            2008 -> "Estampado de Fecha" to if (status == 1) "Activado" else "Desactivado"
            2010 -> "Sensibilidad Sensor-G" to when (status) { 0 -> "Desactivado"; 1 -> "Bajo"; 2 -> "Medio"; 3 -> "Alto"; else -> "Nivel $status" }
            2019 -> "Ángulo de Visión (FOV)" to "Modo $status"
            1002 -> "Resolución de Foto" to (com.sjcam.controller.data.CameraSettings.PHOTO_RESOLUTIONS.find { it.first == status }?.second ?: "Índice $status")
            1005 -> "Balance de Blancos" to (com.sjcam.controller.data.CameraSettings.WHITE_BALANCE_OPTIONS.find { it.first == status }?.second ?: "Índice $status")
            1006 -> "Filtro de Color" to when (status) { 0 -> "Normal"; 1 -> "Blanco y Negro"; 2 -> "Sepia"; else -> "Filtro $status" }
            1007 -> "Sensibilidad ISO" to when (status) { 0 -> "Automático"; 1 -> "100"; 2 -> "200"; 3 -> "400"; 4 -> "800"; 5 -> "1600"; else -> "ISO $status" }
            1008 -> "Modo de Exposición" to "Modo $status"
            1009 -> "Estabilización EIS" to if (status == 1) "Activada" else "Desactivada"
            1012 -> "Nitidez (Sharpness)" to when (status) { 0 -> "Normal"; 1 -> "Fuerte"; 2 -> "Suave"; else -> "Nivel $status" }
            3003 -> "Sonidos Beep del Botón" to if (status == 1) "Activados" else "Silenciados"
            3004 -> "Salida de TV" to if (status == 1) "PAL" else "NTSC"
            3007 -> "Frecuencia de Luz" to when (status) { 0 -> "50 Hz"; 1 -> "60 Hz"; 2 -> "Automático"; else -> "$status Hz" }
            3008 -> "Apagado Automático" to (com.sjcam.controller.data.CameraSettings.AUTO_POWER_OFF_OPTIONS.find { it.first == status }?.second ?: "Índice $status")
            3010 -> "Salvapantallas" to if (status == 0) "Desactivado" else "$status min"
            3025 -> "Wi-Fi Auto-apagado" to "Modo $status"
            9008 -> "Rotación de Imagen 180°" to if (status == 1) "Invertida" else "Normal"
            9010 -> "Modo Nocturno" to if (status == 1) "Activado" else "Desactivado"
            9020 -> "Modo Vehículo (Car Mode)" to if (status == 1) "Activado" else "Desactivado"
            9032 -> "Orientación de Grabación" to when (status) { 0 -> "Horizontal"; 1 -> "Vertical"; 2 -> "Automático"; else -> "Modo $status" }
            9033 -> "Corrección de Distorsión" to if (status == 1) "Activada" else "Desactivada"
            9034 -> "Grabación Rápida" to if (status == 1) "Activada" else "Desactivada"
            9035 -> "Luz LED Indicadora" to if (status == 1) "Activada" else "Desactivada"
            else -> "Ajuste Propietario ($cmd)" to "Valor $status"
        }
    }

    fun setVideoResolution(par: Int) {
        viewModelScope.launch {
            AppLogger.i(TAG, "Cambiando resolución de video a índice $par...")
            apiClient.executeCommand(2002, par.toString()).onSuccess {
                _cameraSettings.update { it.copy(videoResolution = par, statusMessage = "Resolución de video actualizada.") }
            }
        }
    }

    fun setPhotoResolution(par: Int) {
        viewModelScope.launch {
            AppLogger.i(TAG, "Cambiando resolución de foto a índice $par...")
            apiClient.executeCommand(1002, par.toString()).onSuccess {
                _cameraSettings.update { it.copy(photoResolution = par, statusMessage = "Resolución de foto actualizada.") }
            }
        }
    }

    fun setLoopRecording(par: Int) {
        viewModelScope.launch {
            AppLogger.i(TAG, "Cambiando grabación en bucle a índice $par...")
            apiClient.executeCommand(2003, par.toString()).onSuccess {
                _cameraSettings.update { it.copy(loopRecording = par, statusMessage = "Grabación en bucle actualizada.") }
            }
        }
    }

    fun setAudioRecording(enabled: Boolean) {
        viewModelScope.launch {
            val par = if (enabled) "1" else "0"
            AppLogger.i(TAG, "Cambiando audio micrófono a: $enabled (par=$par)...")
            apiClient.executeCommand(2007, par).onSuccess {
                _cameraSettings.update { it.copy(audioEnabled = enabled, statusMessage = if (enabled) "Micrófono activado." else "Micrófono silenciado.") }
            }
        }
    }

    fun setDateStamp(enabled: Boolean) {
        viewModelScope.launch {
            val par = if (enabled) "1" else "0"
            AppLogger.i(TAG, "Cambiando marca de fecha a: $enabled (par=$par)...")
            apiClient.executeCommand(2008, par).onSuccess {
                _cameraSettings.update { it.copy(dateStampEnabled = enabled, statusMessage = if (enabled) "Marca de fecha activada." else "Marca de fecha desactivada.") }
            }
        }
    }

    fun setWdr(enabled: Boolean) {
        viewModelScope.launch {
            val par = if (enabled) "1" else "0"
            AppLogger.i(TAG, "Cambiando WDR a: $enabled...")
            apiClient.executeCommand(2004, par).onSuccess {
                _cameraSettings.update { it.copy(wdrEnabled = enabled, statusMessage = "WDR actualizado.") }
            }
        }
    }

    fun setBeep(enabled: Boolean) {
        viewModelScope.launch {
            val par = if (enabled) "1" else "0"
            AppLogger.i(TAG, "Cambiando sonido Beep a: $enabled (cmd=3003&par=$par)...")
            apiClient.executeCommand(3003, par).onSuccess {
                _cameraSettings.update { it.copy(beepEnabled = enabled, statusMessage = "Sonidos Beep actualizados.") }
            }
        }
    }

    fun setAutoPowerOff(par: Int) {
        viewModelScope.launch {
            AppLogger.i(TAG, "Cambiando apagado automático a índice $par (cmd=3008&par=$par)...")
            apiClient.executeCommand(3008, par.toString()).onSuccess {
                _cameraSettings.update { it.copy(autoPowerOff = par, statusMessage = "Apagado automático actualizado.") }
            }
        }
    }

    fun setWhiteBalance(par: Int) {
        viewModelScope.launch {
            AppLogger.i(TAG, "Cambiando balance de blancos a índice $par (cmd=1005&par=$par)...")
            apiClient.executeCommand(1005, par.toString()).onSuccess {
                _cameraSettings.update { it.copy(whiteBalance = par, statusMessage = "Balance de blancos actualizado.") }
            }
        }
    }

    fun setEvBias(par: Int) {
        viewModelScope.launch {
            AppLogger.i(TAG, "Cambiando compensación EV a índice $par (cmd=2005&par=$par)...")
            apiClient.executeCommand(2005, par.toString()).onSuccess {
                _cameraSettings.update { it.copy(evBias = par, statusMessage = "Compensación EV actualizada.") }
            }
        }
    }

    fun formatSdCard(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            AppLogger.w(TAG, "¡Iniciando formateo de tarjeta MicroSD con cmd=3011&par=1!")
            val res = apiClient.executeCommand(3011, "1")
            res.onSuccess { resp ->
                if (resp.isSuccess) {
                    AppLogger.i(TAG, "Tarjeta SD formateada con éxito.")
                    _cameraSettings.update { it.copy(statusMessage = "¡Tarjeta MicroSD formateada con éxito!") }
                    delay(500)
                    loadAllSettings()
                    onResult(true)
                } else {
                    AppLogger.e(TAG, "Fallo al formatear tarjeta SD: Status=${resp.status}")
                    _cameraSettings.update { it.copy(statusMessage = "Error al formatear SD: Status ${resp.status}") }
                    onResult(false)
                }
            }.onFailure { err ->
                AppLogger.e(TAG, "Error formateando SD: ${err.message}", err)
                onResult(false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopHeartbeat()
        CameraRecordingService.stop(getApplication())
        releaseWakeLock()
        playerManager.release()
        networkManager.releaseNetworkBinding()
    }
}
