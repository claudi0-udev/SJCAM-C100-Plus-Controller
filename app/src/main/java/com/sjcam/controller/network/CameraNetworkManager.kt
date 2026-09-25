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

    private var specificNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Conexión directa a la red Wi-Fi de la cámara desde dentro de la app
     * mediante WifiNetworkSpecifier (Android 10+).
     */
    fun connectDirectToWifi(ssid: String, passphrase: String, onStatus: (String) -> Unit) {
        val cleanSsid = ssid.trim()
        val cleanPass = passphrase.trim()
        AppLogger.i(TAG, "Iniciando solicitud in-app para conectar a Wi-Fi: $cleanSsid...")
        onStatus("Solicitando conexión a $cleanSsid...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val specifier = if (cleanPass.isNotBlank()) {
                    android.net.wifi.WifiNetworkSpecifier.Builder()
                        .setSsid(cleanSsid)
                        .setWpa2Passphrase(cleanPass)
                        .build()
                } else {
                    android.net.wifi.WifiNetworkSpecifier.Builder()
                        .setSsid(cleanSsid)
                        .build()
                }

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()

                specificNetworkCallback?.let {
                    try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
                }

                specificNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        val bound = connectivityManager.bindProcessToNetwork(network)
                        _isBoundToWifi.value = bound
                        _currentWifiSsid.value = cleanSsid
                        AppLogger.i(TAG, "¡Conectado in-app a Wi-Fi $cleanSsid! bindProcessToNetwork=$bound")
                        onStatus("¡Conectado a $cleanSsid!")
                    }

                    override fun onUnavailable() {
                        AppLogger.w(TAG, "Conexión a $cleanSsid cancelada o red no encontrada.")
                        onStatus("Conexión cancelada o red no disponible.")
                    }

                    override fun onLost(network: Network) {
                        AppLogger.w(TAG, "Conexión con $cleanSsid perdida.")
                        _isBoundToWifi.value = false
                        onStatus("Conexión perdida.")
                    }
                }

                connectivityManager.requestNetwork(request, specificNetworkCallback!!)
                onStatus("Confirma la conexión a $cleanSsid en el aviso en pantalla...")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error en connectDirectToWifi: ${e.message}", e)
                onStatus("Error: ${e.message}")
            }
        } else {
            onStatus("Android 9 o anterior: Usa el panel Wi-Fi para conectarte a $cleanSsid.")
            openSystemWifiSettings()
        }
    }

    /**
     * Abre el panel flotante de Wi-Fi de Android sobre la app sin salir a Ajustes.
     */
    fun openSystemWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val panelIntent = android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
            } else {
                val wifiIntent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(wifiIntent)
            }
        } catch (e: Exception) {
            val fallback = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
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
            specificNetworkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
                specificNetworkCallback = null
            }
            _isBoundToWifi.value = false
            AppLogger.i(TAG, "Binding de red Wi-Fi liberado.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error liberando NetworkCallback: ${e.message}", e)
        }
    }
}
