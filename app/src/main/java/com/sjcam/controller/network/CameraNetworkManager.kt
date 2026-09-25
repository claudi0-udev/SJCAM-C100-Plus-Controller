package com.sjcam.controller.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.sjcam.controller.data.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraNetworkManager(private val context: Context) {

    private val TAG = "NetworkManager"
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val _isBoundToWifi = MutableStateFlow(false)
    val isBoundToWifi: StateFlow<Boolean> = _isBoundToWifi.asStateFlow()

    private val _currentWifiSsid = MutableStateFlow<String?>(null)
    val currentWifiSsid: StateFlow<String?> = _currentWifiSsid.asStateFlow()

    /**
     * Fuerza a Android a enrutar todo el tráfico de red de la aplicación
     * exclusivamente por la interfaz Wi-Fi (incluso si no tiene salida a Internet).
     */
    fun bindProcessToWifiNetwork() {
        AppLogger.i(TAG, "Iniciando solicitud para forzar tráfico por Wi-Fi...")

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Importante: no requiere internet
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val bound = connectivityManager.bindProcessToNetwork(network)
                AppLogger.i(TAG, "Wi-Fi detectada. bindProcessToNetwork ejecutado. Resultado: $bound")
                _isBoundToWifi.value = bound
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                AppLogger.w(TAG, "Conexión Wi-Fi perdida. Desvinculando proceso de la red.")
                connectivityManager.bindProcessToNetwork(null)
                _isBoundToWifi.value = false
            }

            override fun onUnavailable() {
                super.onUnavailable()
                AppLogger.w(TAG, "Red Wi-Fi no disponible.")
                _isBoundToWifi.value = false
            }
        }

        try {
            connectivityManager.requestNetwork(request, networkCallback!!)
            AppLogger.d(TAG, "NetworkCallback registrado satisfactoriamente.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error al registrar NetworkCallback: ${e.message}", e)
        }
    }

    /**
     * Libera el binding de red y vuelve a la configuración por defecto de Android.
     */
    fun releaseNetworkBinding() {
        try {
            connectivityManager.bindProcessToNetwork(null)
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
                networkCallback = null
            }
            _isBoundToWifi.value = false
            AppLogger.i(TAG, "Binding de red Wi-Fi liberado.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error liberando NetworkCallback: ${e.message}", e)
        }
    }
}
