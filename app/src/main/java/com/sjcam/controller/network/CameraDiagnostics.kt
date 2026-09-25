package com.sjcam.controller.network

import com.sjcam.controller.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

object CameraDiagnostics {

    private const val TAG = "Diagnostics"

    /**
     * Escanea los puertos TCP más comunes en chipsets Novatek / cámaras IP.
     */
    suspend fun runPortScan(cameraIp: String = "192.168.1.254"): List<Int> =
        withContext(Dispatchers.IO) {
            val portsToTest = listOf(80, 554, 8000, 8080, 8192, 5554, 7070)
            val openPorts = mutableListOf<Int>()
            AppLogger.i(TAG, "Iniciando escaneo de puertos en $cameraIp: $portsToTest...")

            for (port in portsToTest) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(cameraIp, port), 600)
                        openPorts.add(port)
                        AppLogger.i(TAG, "--> Puerto $port: ABIERTO ✅")
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Puerto $port: cerrado/sin respuesta.")
                }
            }

            AppLogger.i(TAG, "Escaneo finalizado. Puertos abiertos encontrados: $openPorts")
            openPorts
        }

    /**
     * Envía comandos RTSP en crudo por socket TCP al puerto 554 para
     * descubrir qué rutas RTSP devuelven 200 OK y su contenido SDP.
     */
    suspend fun probeRtspPaths(cameraIp: String = "192.168.1.254") =
        withContext(Dispatchers.IO) {
            val candidatePaths = listOf(
                "/sjcam.mov",
                "/xxx.mov",
                "/live",
                "/stream0",
                "/liveRTSP/v4",
                "/video",
                ""
            )

            AppLogger.rtsp(TAG, "Probando rutas RTSP DESCRIBE directamente en $cameraIp:554...")

            for (path in candidatePaths) {
                try {
                    Socket().use { socket ->
                        socket.soTimeout = 1500
                        socket.connect(InetSocketAddress(cameraIp, 554), 1000)

                        val out: OutputStream = socket.getOutputStream()
                        val fullUrl = "rtsp://$cameraIp$path"
                        val request = "DESCRIBE $fullUrl RTSP/1.0\r\n" +
                                "CSeq: 1\r\n" +
                                "User-Agent: SJCAM-Controller\r\n" +
                                "Accept: application/sdp\r\n\r\n"

                        out.write(request.toByteArray(Charsets.US_ASCII))
                        out.flush()

                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val responseSb = StringBuilder()
                        var line: String?

                        // Leer hasta final de cabecera/cuerpo disponible
                        while (reader.readLine().also { line = it } != null) {
                            responseSb.append(line).append("\n")
                            if (responseSb.contains("Content-Length: 0") || responseSb.contains("m=video")) {
                                break
                            }
                        }

                        val fullResp = responseSb.toString()
                        val statusLine = fullResp.lines().firstOrNull() ?: "Sin respuesta"

                        if (statusLine.contains("200")) {
                            AppLogger.rtsp(TAG, "¡RUTA VÁLIDA ENCONTRADA! $fullUrl -> $statusLine")
                            AppLogger.rtsp(TAG, "SDP Recibido:\n$fullResp")
                        } else {
                            AppLogger.d(TAG, "Ruta $path -> $statusLine")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Error probando ruta $path: ${e.message}")
                }
            }
        }
}
