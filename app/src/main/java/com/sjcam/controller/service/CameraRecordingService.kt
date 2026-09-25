package com.sjcam.controller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sjcam.controller.MainActivity
import com.sjcam.controller.data.AppLogger

/**
 * Foreground Service que mantiene el enlace Wi-Fi con la cámara SJCAM
 * y la grabación en disco activo de forma ininterrumpida cuando la pantalla
 * del teléfono se suspende o se guarda en el bolsillo.
 */
class CameraRecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "sjcam_recording_channel"
        const val ACTION_START = "com.sjcam.controller.action.START_RECORDING_SERVICE"
        const val ACTION_STOP = "com.sjcam.controller.action.STOP_RECORDING_SERVICE"

        fun start(context: Context) {
            try {
                val intent = Intent(context, CameraRecordingService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                AppLogger.i(TAG, "Solicitud de inicio de Foreground Service enviada.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error iniciando CameraRecordingService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, CameraRecordingService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
                AppLogger.i(TAG, "Solicitud de parada de Foreground Service enviada.")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error deteniendo CameraRecordingService: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "CameraRecordingService onCreate.")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLogger.i(TAG, "CameraRecordingService: Acción STOP recibida.")
            releaseLocks()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        AppLogger.i(TAG, "CameraRecordingService: Iniciando en primer plano con WakeLock, WifiLock y MulticastLock.")
        val notification = buildNotification()

        val foregroundTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundTypes)
            AppLogger.i(TAG, "startForeground iniciado con tipos: $foregroundTypes")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error iniciando startForeground con tipos ($foregroundTypes): ${e.message}. Reintentando sin tipos...")
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
            } catch (e2: Exception) {
                AppLogger.e(TAG, "Fallo al iniciar startForeground: ${e2.message}", e2)
            }
        }

        acquireLocks()

        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "com.sjcam.controller:ServiceWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60 * 60 * 1000L /* 1 hora máx de seguridad */)
                AppLogger.i(TAG, "WakeLock en ForegroundService adquirido exitosamente.")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error al adquirir WakeLock en servicio: ${e.message}")
        }

        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager.createWifiLock(
                    wifiMode,
                    "com.sjcam.controller:ServiceWifiLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                AppLogger.i(TAG, "WifiLock adquirido: Wi-Fi de la cámara blindado contra suspensión.")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error al adquirir WifiLock en servicio: ${e.message}")
        }

        try {
            if (multicastLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock("com.sjcam.controller:ServiceMulticastLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
                AppLogger.i(TAG, "MulticastLock adquirido: Hardware Wi-Fi forzado a máxima potencia sin ahorro de energía.")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error al adquirir MulticastLock en servicio: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLogger.i(TAG, "WakeLock en servicio liberado.")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error liberando WakeLock en servicio: ${e.message}")
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                AppLogger.i(TAG, "WifiLock en servicio liberado.")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error liberando WifiLock en servicio: ${e.message}")
        }
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                AppLogger.i(TAG, "MulticastLock en servicio liberado.")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error liberando MulticastLock en servicio: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Grabación SJCAM en Celular",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene la grabación y conexión continua con la cámara en segundo plano"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 Grabando Video en Celular")
            .setContentText("Grabación continua activa en segundo plano (Movies/SJCAM)")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "CameraRecordingService destruido.")
        releaseLocks()
        super.onDestroy()
    }
}
