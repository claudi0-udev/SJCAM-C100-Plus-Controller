package com.sjcam.controller.network

import com.sjcam.controller.data.AppLogger
import com.sjcam.controller.data.CameraMode
import com.sjcam.controller.data.NovatekResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class SjcamApiClient(var cameraBaseUrl: String = "http://192.168.1.254") {

    private val TAG = "SjcamApiClient"

    private val loggingInterceptor = Interceptor { chain ->
        val request: Request = chain.request()
        val url = request.url.toString()
        val startNs = System.nanoTime()

        AppLogger.http(TAG, "--> ${request.method} $url")

        try {
            val response: Response = chain.proceed(request)
            val durationMs = (System.nanoTime() - startNs) / 1_000_000

            // Clonar o leer el cuerpo para no consumirlo
            val responseBody = response.body
            val bodyString = responseBody?.string() ?: ""

            AppLogger.http(
                TAG,
                "<-- ${response.code} ${response.message} (${durationMs}ms) para $url",
                bodyString
            )

            // Reconstruir respuesta con el body leído
            response.newBuilder()
                .body(okhttp3.ResponseBody.create(responseBody?.contentType(), bodyString))
                .build()
        } catch (e: Exception) {
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            AppLogger.e(TAG, "<-- Falla de red tras ${durationMs}ms para $url: ${e.message}", e)
            throw e
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Envía un comando Novatek genérico: http://192.168.1.254/?custom=1&cmd=<cmd>[&par=<par>]
     */
    suspend fun executeCommand(cmd: Int, par: String? = null): Result<NovatekResponse> =
        withContext(Dispatchers.IO) {
            val urlBuilder = StringBuilder("$cameraBaseUrl/?custom=1&cmd=$cmd")
            if (!par.isNullOrBlank()) {
                urlBuilder.append("&par=$par")
            }
            val url = urlBuilder.toString()

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val parsed = NovatekResponse.parse(body)
                    Result.success(parsed)
                }
            } catch (e: IOException) {
                AppLogger.e(TAG, "Error de E/S ejecutando comando cmd=$cmd: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Excepción inesperada en comando cmd=$cmd: ${e.message}", e)
                Result.failure(e)
            }
        }

    suspend fun takePhoto(): Result<NovatekResponse> = executeCommand(1001)

    suspend fun startRecording(): Result<NovatekResponse> = executeCommand(2001, "1")

    suspend fun stopRecording(): Result<NovatekResponse> = executeCommand(2001, "0")

    suspend fun enableLiveStream(): Result<NovatekResponse> = executeCommand(2015, "1")

    suspend fun disableLiveStream(): Result<NovatekResponse> = executeCommand(2015, "0")

    suspend fun getBattery(): Result<NovatekResponse> = executeCommand(3019)

    suspend fun getFreeSpace(): Result<NovatekResponse> = executeCommand(3017)

    suspend fun setCameraMode(mode: CameraMode): Result<NovatekResponse> =
        executeCommand(3001, mode.code.toString())

    suspend fun listMedia(): Result<NovatekResponse> = executeCommand(3015)

    suspend fun executeRawUrl(pathAndQuery: String): Result<String> =
        withContext(Dispatchers.IO) {
            val normalizedPath = if (pathAndQuery.startsWith("/")) pathAndQuery else "/$pathAndQuery"
            val fullUrl = "$cameraBaseUrl$normalizedPath"
            val request = Request.Builder().url(fullUrl).get().build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    Result.success(body)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
