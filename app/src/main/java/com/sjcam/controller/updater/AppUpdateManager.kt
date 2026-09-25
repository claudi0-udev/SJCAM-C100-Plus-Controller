package com.sjcam.controller.updater

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.sjcam.controller.BuildConfig
import com.sjcam.controller.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val apkSize: Long,
    val isNewer: Boolean
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateState()
    object UpToDate : UpdateState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateState()
    data class ReadyToInstall(val apkFile: File, val release: ReleaseInfo) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class AppUpdateManager(private val context: Context) {

    private val TAG = "AppUpdateManager"
    val GITHUB_OWNER = "claudi0-udev"
    val GITHUB_REPO = "SJCAM-C100-Plus-Controller"

    val currentVersionName: String = BuildConfig.VERSION_NAME

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Crea un cliente OkHttp configurado para enrutar el tráfico por una red con Internet
     * (incluso si el proceso Android está enlazado a la red Wi-Fi de la cámara sin Internet).
     */
    private fun getInternetOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        try {
            val internetNetwork: Network? = connectivityManager.allNetworks.firstOrNull { net ->
                val caps = connectivityManager.getNetworkCapabilities(net) ?: return@firstOrNull false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                         caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            } ?: connectivityManager.allNetworks.firstOrNull { net ->
                val caps = connectivityManager.getNetworkCapabilities(net) ?: return@firstOrNull false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }

            if (internetNetwork != null) {
                AppLogger.i(TAG, "Enrutando peticiones del actualizador por interfaz con Internet: $internetNetwork")
                builder.socketFactory(internetNetwork.socketFactory)
                builder.dns(object : Dns {
                    override fun lookup(hostname: String): List<java.net.InetAddress> {
                        return try {
                            internetNetwork.getAllByName(hostname).toList()
                        } catch (e: Exception) {
                            Dns.SYSTEM.lookup(hostname)
                        }
                    }
                })
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "No se pudo vincular socket a red con Internet: ${e.message}")
        }

        return builder.build()
    }

    suspend fun checkForUpdates(): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        _updateState.value = UpdateState.Checking
        AppLogger.i(TAG, "Buscando actualizaciones en GitHub: $GITHUB_OWNER/$GITHUB_REPO...")

        val client = getInternetOkHttpClient()
        val url = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "SJCAM-C100-Plus-App/$currentVersionName")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val errMsg = when (code) {
                        404 -> "No se encontraron releases públicos en el repositorio todavía."
                        403 -> "Límite de peticiones de GitHub API alcanzado. Intenta de nuevo más tarde."
                        else -> "Error consultando GitHub (HTTP $code)"
                    }
                    AppLogger.w(TAG, errMsg)
                    _updateState.value = UpdateState.Error(errMsg)
                    return@withContext Result.failure(Exception(errMsg))
                }

                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val tagName = json.optString("tag_name", "").trim()
                val title = json.optString("name", tagName)
                val changelog = json.optString("body", "Sin notas de versión.")

                val assets = json.optJSONArray("assets")
                var downloadUrl: String? = null
                var apkSize: Long = 0

                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val assetName = asset.optString("name", "")
                        if (assetName.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url")
                            apkSize = asset.optLong("size", 0)
                            break
                        }
                    }
                }

                if (downloadUrl.isNullOrBlank()) {
                    val err = "La última versión ($tagName) no tiene ningún archivo .apk adjunto."
                    _updateState.value = UpdateState.Error(err)
                    return@withContext Result.failure(Exception(err))
                }

                val isNewer = isVersionNewer(tagName, currentVersionName)
                val releaseInfo = ReleaseInfo(
                    tagName = tagName,
                    title = title,
                    changelog = changelog,
                    downloadUrl = downloadUrl,
                    apkSize = apkSize,
                    isNewer = isNewer
                )

                AppLogger.i(TAG, "Versión remota: $tagName (Actual: $currentVersionName). ¿Es nueva?: $isNewer")

                if (isNewer) {
                    _updateState.value = UpdateState.UpdateAvailable(releaseInfo)
                } else {
                    _updateState.value = UpdateState.UpToDate
                }

                Result.success(releaseInfo)
            }
        } catch (e: Exception) {
            val errorMsg = if (e.message?.contains("Unable to resolve host", ignoreCase = true) == true) {
                "Sin acceso a Internet. Desconéctate temporalmente del Wi-Fi de la cámara o activa tus datos móviles para buscar actualizaciones."
            } else {
                "Error al buscar actualizaciones: ${e.message}"
            }
            AppLogger.e(TAG, errorMsg, e)
            _updateState.value = UpdateState.Error(errorMsg)
            Result.failure(Exception(errorMsg, e))
        }
    }

    suspend fun downloadAndPrepareInstall(release: ReleaseInfo) = withContext(Dispatchers.IO) {
        val client = getInternetOkHttpClient()
        val updateDir = File(context.cacheDir, "updates")
        if (!updateDir.exists()) updateDir.mkdirs()

        val cleanTag = release.tagName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val apkFile = File(updateDir, "SJCAM-C100-Plus-$cleanTag.apk")

        // Si ya está descargado y coincide en tamaño, saltar descarga
        if (apkFile.exists() && release.apkSize > 0 && apkFile.length() == release.apkSize) {
            AppLogger.i(TAG, "El archivo APK ya existe en caché con el tamaño correcto. Listo para instalar.")
            _updateState.value = UpdateState.ReadyToInstall(apkFile, release)
            return@withContext
        }

        _updateState.value = UpdateState.Downloading(0f, 0, release.apkSize)
        AppLogger.i(TAG, "Iniciando descarga de ${release.downloadUrl} a ${apkFile.absolutePath}...")

        val request = Request.Builder()
            .url(release.downloadUrl)
            .header("User-Agent", "SJCAM-C100-Plus-App/$currentVersionName")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = "Fallo en la descarga del APK (HTTP ${response.code})"
                    _updateState.value = UpdateState.Error(err)
                    return@withContext
                }

                val body = response.body ?: throw Exception("Cuerpo de respuesta vacío")
                val totalBytes = if (release.apkSize > 0) release.apkSize else body.contentLength()

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(apkFile)

                val buffer = ByteArray(16 * 1024)
                var downloaded: Long = 0
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    downloaded += read
                    val progress = if (totalBytes > 0) downloaded.toFloat() / totalBytes.toFloat() else 0f
                    _updateState.value = UpdateState.Downloading(progress.coerceIn(0f, 1f), downloaded, totalBytes)
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                AppLogger.i(TAG, "Descarga completada: ${apkFile.length()} bytes.")
                _updateState.value = UpdateState.ReadyToInstall(apkFile, release)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error durante la descarga del APK: ${e.message}", e)
            _updateState.value = UpdateState.Error("Fallo en la descarga: ${e.message}")
        }
    }

    fun promptInstall(apkFile: File) {
        if (!apkFile.exists()) {
            _updateState.value = UpdateState.Error("El archivo de actualización no se encuentra en el dispositivo.")
            return
        }

        try {
            // Verificar permiso de instalación de paquetes desconocidos en Android 8.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    AppLogger.w(TAG, "Solicitando permiso de orígenes desconocidos al usuario...")
                    val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(permissionIntent)
                    return
                }
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            AppLogger.i(TAG, "Lanzando intent de instalación con URI: $contentUri")
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error al lanzar el instalador de Android: ${e.message}", e)
            _updateState.value = UpdateState.Error("No se pudo iniciar la instalación: ${e.message}")
        }
    }

    fun resetState() {
        _updateState.value = UpdateState.Idle
    }

    private fun isVersionNewer(remoteTag: String, currentTag: String): Boolean {
        fun cleanVersion(str: String): List<Int> {
            val numbers = str.trim().removePrefix("v").removePrefix("V").split("-")[0].split(".")
            return numbers.mapNotNull { it.toIntOrNull() }
        }

        val remoteParts = cleanVersion(remoteTag)
        val currentParts = cleanVersion(currentTag)

        val maxLength = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLength) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
