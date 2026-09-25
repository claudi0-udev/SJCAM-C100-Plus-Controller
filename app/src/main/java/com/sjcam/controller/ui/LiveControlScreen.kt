package com.sjcam.controller.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BatteryUnknown
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sjcam.controller.viewmodel.CameraViewModel
import org.videolan.libvlc.util.VLCVideoLayout

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveControlScreen(viewModel: CameraViewModel) {
    val status by viewModel.cameraStatus.collectAsState()
    val isBoundToWifi by viewModel.networkManager.isBoundToWifi.collectAsState()
    val isStreamPlaying by viewModel.playerManager.isPlaying.collectAsState()
    val streamError by viewModel.playerManager.errorMessage.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(isStreamPlaying) {
        val window = (context as? android.app.Activity)?.window
        if (isStreamPlaying) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var showConfigDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141414))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Barra Superior: Estado de conexión y batería
        Surface(
            color = Color(0xFF222222),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Estado Wi-Fi
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (isBoundToWifi) Color(0xFF00E676) else Color(0xFFFF5252),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isBoundToWifi) "Wi-Fi Enlazada" else "Sin Enlace Wi-Fi",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }

                // Batería
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (status.batteryLevel != null) Icons.Default.BatteryChargingFull else Icons.AutoMirrored.Filled.BatteryUnknown,
                        contentDescription = "Batería",
                        tint = if (status.batteryLevel != null) Color(0xFF00E5FF) else Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = status.batteryDisplay(),
                        color = Color.White,
                        fontSize = 12.sp
                    )

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        onClick = { showConfigDialog = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Ajustes IP y RTSP", tint = Color.LightGray)
                    }
                }
            }
        }

        // Alerta de error si existe
        if (status.lastError != null || streamError != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = Color(0x33FF5252),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = status.lastError ?: streamError ?: "",
                    color = Color(0xFFFF8A80),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Visor de Video en Directo (RTSP Media3 SurfaceView)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black, RoundedCornerShape(16.dp))
                .border(1.dp, if (status.isRecording) Color.Red else Color(0xFF333333), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VLCVideoLayout(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        viewModel.playerManager.initializePlayer()
                        viewModel.playerManager.attachLayout(this)
                    }
                },
                update = { layout ->
                    viewModel.playerManager.attachLayout(layout)
                }
            )

            DisposableEffect(Unit) {
                onDispose {
                    viewModel.playerManager.detachLayout()
                }
            }

            // Indicador de Grabando en esquina
            if (status.isRecording) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color.Red, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("REC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            // Overlay cuando el stream no está reproduciendo
            if (!isStreamPlaying) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = if (status.isLiveStreaming) "Conectando a:\n${status.rtspUrl}..." else "Previsualización detenida",
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.toggleLiveStream() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
                    ) {
                        Icon(
                            imageVector = if (status.isLiveStreaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = "Stream"
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (status.isLiveStreaming) "Detener Stream" else "Iniciar Previsualización")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Controles de Acción (Botones principales)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botón Verificar Conexión
            IconButton(
                onClick = { viewModel.checkConnectionAndBattery() },
                modifier = Modifier
                    .size(52.dp)
                    .background(Color(0xFF2C2C2C), CircleShape)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refrescar", tint = Color.White)
            }

            // Botón Grabar Video (Principal)
            Button(
                onClick = { viewModel.toggleRecording() },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (status.isRecording) Color.Red else Color(0xFFFF5252)
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = if (status.isRecording) Icons.Default.Stop else Icons.Default.Videocam,
                    contentDescription = "Grabar",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Botón Tomar Foto
            IconButton(
                onClick = { viewModel.takePhoto() },
                modifier = Modifier
                    .size(52.dp)
                    .background(Color.White, CircleShape)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Foto", tint = Color.Black)
            }
        }
    }

    // Modal de Configuración de IP, RTSP Presets y TCP/UDP
    if (showConfigDialog) {
        var ipInput by remember { mutableStateOf(status.cameraIp) }
        var rtspInput by remember { mutableStateOf(status.rtspUrl) }
        var forceTcpChecked by remember { mutableStateOf(status.forceTcp) }

        val presets = listOf(
            "sjcam.mov" to "rtsp://$ipInput/sjcam.mov",
            "stream0" to "rtsp://$ipInput/stream0",
            "xxx.mov" to "rtsp://$ipInput/xxx.mov",
            "live" to "rtsp://$ipInput/live",
            "raíz (/)" to "rtsp://$ipInput"
        )

        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = { Text("Ajustes de Stream RTSP") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("IP de la Cámara") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = rtspInput,
                        onValueChange = { rtspInput = it },
                        label = { Text("URL de Stream RTSP") },
                        singleLine = true
                    )

                    Text("Rutas RTSP Frecuentes de SJCAM:", fontSize = 12.sp, color = Color.Gray)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presets.forEach { (label, url) ->
                            SuggestionChip(
                                onClick = { rtspInput = url },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Forzar RTP sobre TCP", fontSize = 13.sp)
                        Switch(
                            checked = forceTcpChecked,
                            onCheckedChange = { forceTcpChecked = it }
                        )
                    }
                    Text(
                        text = if (forceTcpChecked) "TCP activo (más fiable). Desactívalo si la cámara solo emite UDP."
                        else "UDP activo (menor latencia).",
                        fontSize = 11.sp,
                        color = Color.DarkGray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateCameraIp(ipInput)
                        viewModel.updateRtspUrl(rtspInput)
                        viewModel.setForceTcp(forceTcpChecked)
                        showConfigDialog = false
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
