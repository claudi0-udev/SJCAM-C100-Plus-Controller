package com.sjcam.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sjcam.controller.data.CameraSettings
import com.sjcam.controller.viewmodel.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: CameraViewModel) {
    val settings by viewModel.cameraSettings.collectAsState()
    var showFormatDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadAllSettings()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141414))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Encabezado
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Ajustes de Cámara",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "SJCAM C100+ (Novatek NTK96675)",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            IconButton(
                onClick = { viewModel.loadAllSettings() },
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFF00E5FF))
            ) {
                if (settings.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF00E5FF))
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Recargar Ajustes")
                }
            }
        }

        // Mensaje de estado / notificación
        settings.statusMessage?.let { msg ->
            Surface(
                color = Color(0xFF1E3A40),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = msg,
                    color = Color(0xFF80DEEA),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // Tarjeta: Video
        SettingsCard(title = "Video y Grabación", icon = Icons.Default.Videocam) {
            // Resolución Video
            SettingsDropdown(
                label = "Resolución de Video",
                options = CameraSettings.VIDEO_RESOLUTIONS,
                selectedKey = settings.videoResolution,
                onSelected = { viewModel.setVideoResolution(it) }
            )

            HorizontalDivider(color = Color(0xFF2C2C2C))

            // Grabación en bucle
            SettingsDropdown(
                label = "Grabación en Bucle (Loop)",
                options = CameraSettings.LOOP_RECORDING_OPTIONS,
                selectedKey = settings.loopRecording,
                onSelected = { viewModel.setLoopRecording(it) }
            )

            HorizontalDivider(color = Color(0xFF2C2C2C))

            // Audio del Micrófono
            SettingsSwitch(
                title = "Grabar Audio con Micrófono",
                subtitle = "Captura sonido ambiente en las grabaciones",
                checked = settings.audioEnabled ?: true,
                onCheckedChange = { viewModel.setAudioRecording(it) }
            )

            HorizontalDivider(color = Color(0xFF2C2C2C))

            // Marca de Agua Fecha
            SettingsSwitch(
                title = "Marca de Fecha y Hora",
                subtitle = "Inserta la marca de tiempo en el video",
                checked = settings.dateStampEnabled ?: true,
                onCheckedChange = { viewModel.setDateStamp(it) }
            )

            HorizontalDivider(color = Color(0xFF2C2C2C))

            // WDR
            SettingsSwitch(
                title = "Rango Dinámico Amplio (WDR)",
                subtitle = "Mejora exposición en contraluces",
                checked = settings.wdrEnabled ?: false,
                onCheckedChange = { viewModel.setWdr(it) }
            )

            HorizontalDivider(color = Color(0xFF2C2C2C))

            // Compensación EV
            SettingsDropdown(
                label = "Compensación de Exposición (EV)",
                options = CameraSettings.EV_BIAS_OPTIONS,
                selectedKey = settings.evBias,
                onSelected = { viewModel.setEvBias(it) }
            )
        }

        // Tarjeta: Fotografía
        SettingsCard(title = "Fotografía", icon = Icons.Default.PhotoCamera) {
            SettingsDropdown(
                label = "Resolución de Foto",
                options = CameraSettings.PHOTO_RESOLUTIONS,
                selectedKey = settings.photoResolution,
                onSelected = { viewModel.setPhotoResolution(it) }
            )

            HorizontalDivider(color = Color(0xFF2C2C2C))

            SettingsDropdown(
                label = "Balance de Blancos",
                options = CameraSettings.WHITE_BALANCE_OPTIONS,
                selectedKey = settings.whiteBalance,
                onSelected = { viewModel.setWhiteBalance(it) }
            )
        }

        // Tarjeta: Sistema
        SettingsCard(title = "Sistema y Comportamiento", icon = Icons.Default.Tune) {
            SettingsSwitch(
                title = "Sonido Beep de Botón",
                subtitle = "Pitidos acústicos al presionar el obturador",
                checked = settings.beepEnabled ?: true,
                onCheckedChange = { viewModel.setBeep(it) }
            )

            HorizontalDivider(color = Color(0xFF2C2C2C))

            SettingsDropdown(
                label = "Apagado Automático",
                options = CameraSettings.AUTO_POWER_OFF_OPTIONS,
                selectedKey = settings.autoPowerOff,
                onSelected = { viewModel.setAutoPowerOff(it) }
            )

            HorizontalDivider(color = Color(0xFF2C2C2C))

            // Firmware
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Firmware", color = Color.White, fontSize = 13.sp)
                Text(
                    text = settings.firmwareVersion ?: "V1.3.8 (Jun 18 2024)",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            }
        }

        // Tarjeta: Almacenamiento MicroSD
        SettingsCard(title = "Tarjeta MicroSD", icon = Icons.Default.SdCard) {
            val freeMb = settings.freeSpaceMb
            val totalMb = settings.totalSpaceMb

            if (totalMb != null && totalMb > 0) {
                val usedMb = totalMb - (freeMb ?: 0)
                val progress = (usedMb.toFloat() / totalMb.toFloat()).coerceIn(0f, 1f)
                val freeGbStr = String.format(java.util.Locale.US, "%.1f", (freeMb ?: 0).toFloat() / 1024f)
                val totalGbStr = String.format(java.util.Locale.US, "%.1f", totalMb.toFloat() / 1024f)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "$freeGbStr GB libres",
                            color = Color(0xFF00E676),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "$totalGbStr GB total",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                    }

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = Color(0xFF00E5FF),
                        trackColor = Color(0xFF333333)
                    )
                }
            } else {
                Text(
                    "Espacio disponible: Consulta en curso o MicroSD no detectada.",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { showFormatDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB71C1C),
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = "Formatear")
                Spacer(Modifier.width(8.dp))
                Text("Formatear Tarjeta MicroSD")
            }
        }

        // Tarjeta: Volcado Detallado de Configuración Leída
        var isDetailsExpanded by remember { mutableStateOf(true) }
        SettingsCard(title = "Valores Actuales Leídos de la Cámara", icon = Icons.Default.FactCheck) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${settings.allReadSettings.size} parámetros leídos",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
                TextButton(onClick = { isDetailsExpanded = !isDetailsExpanded }) {
                    Text(if (isDetailsExpanded) "Ocultar lista" else "Ver lista")
                }
            }

            if (isDetailsExpanded) {
                if (settings.allReadSettings.isEmpty()) {
                    Text(
                        "Presiona el botón de refrescar superior para leer los valores de la cámara.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        settings.allReadSettings.forEach { item ->
                            Surface(
                                color = Color(0xFF1E1E1E),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text("cmd=${item.cmd} | valor=${item.value}", color = Color.Gray, fontSize = 11.sp)
                                    }
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text(item.readableText, fontSize = 11.sp, color = Color(0xFF00E5FF)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Tarjeta: Actualizaciones de la Aplicación
        val updateState by viewModel.updateState.collectAsState()
        SettingsCard(title = "Actualizaciones de la Aplicación", icon = Icons.Default.SystemUpdate) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Versión de la App", color = Color.Gray, fontSize = 11.sp)
                    Text(
                        text = "v${viewModel.updateManager.currentVersionName}",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (updateState is com.sjcam.controller.updater.UpdateState.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF00E5FF)
                    )
                } else {
                    OutlinedButton(
                        onClick = { viewModel.checkForAppUpdates() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Buscar Novedades", fontSize = 12.sp)
                    }
                }
            }

            when (val state = updateState) {
                is com.sjcam.controller.updater.UpdateState.UpToDate -> {
                    Surface(
                        color = Color(0xFF1B382B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(18.dp))
                            Text("¡Tu aplicación está actualizada a la última versión!", color = Color(0xFFB9F6CA), fontSize = 12.sp)
                        }
                    }
                }

                is com.sjcam.controller.updater.UpdateState.UpdateAvailable -> {
                    Surface(
                        color = Color(0xFF1A2F3B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.NewReleases, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                                Text("¡Nueva versión ${state.release.tagName} disponible!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }

                            if (state.release.changelog.isNotBlank()) {
                                Text(
                                    text = state.release.changelog.take(200),
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }

                            val mbSize = if (state.release.apkSize > 0) String.format(java.util.Locale.US, " (%.1f MB)", state.release.apkSize.toFloat() / (1024f * 1024f)) else ""
                            Button(
                                onClick = { viewModel.downloadAppUpdate(state.release) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF), contentColor = Color.Black),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Descargar e Instalar$mbSize", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                is com.sjcam.controller.updater.UpdateState.Downloading -> {
                    Surface(
                        color = Color(0xFF222222),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Descargando actualización...", color = Color.White, fontSize = 12.sp)
                                Text("${(state.progress * 100).toInt()}%", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = Color(0xFF00E5FF),
                                trackColor = Color(0xFF444444)
                            )

                            val dlMb = String.format(java.util.Locale.US, "%.1f", state.downloadedBytes.toFloat() / (1024f * 1024f))
                            val totMb = if (state.totalBytes > 0) String.format(java.util.Locale.US, "%.1f", state.totalBytes.toFloat() / (1024f * 1024f)) else "--"
                            Text("$dlMb MB / $totMb MB", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }

                is com.sjcam.controller.updater.UpdateState.ReadyToInstall -> {
                    Surface(
                        color = Color(0xFF1B382B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("¡Descarga completada! Lista para instalar.", color = Color(0xFFB9F6CA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Button(
                                onClick = { viewModel.installAppUpdate(state.apkFile) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Instalar Actualización Ahora", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                is com.sjcam.controller.updater.UpdateState.Error -> {
                    Surface(
                        color = Color(0xFF381B1B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(state.message, color = Color(0xFFFF8A80), fontSize = 12.sp)
                            TextButton(
                                onClick = { viewModel.checkForAppUpdates() },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF8A80))
                            ) {
                                Text("Reintentar búsqueda", fontSize = 11.sp)
                            }
                        }
                    }
                }

                else -> {}
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    // Diálogo de Confirmación para Formatear
    if (showFormatDialog) {
        AlertDialog(
            onDismissRequest = { showFormatDialog = false },
            title = { Text("¿Formatear Tarjeta MicroSD?") },
            text = {
                Text(
                    "Esta acción borrará TODOS los videos y fotos almacenados en la tarjeta MicroSD de la cámara.\n\n¿Estás seguro de que deseas continuar?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFormatDialog = false
                        viewModel.formatSdCard { }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Sí, Formatear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFormatDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = Color(0xFF222222),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            content()
        }
    }
}

@Composable
fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color.Gray, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDropdown(
    label: String,
    options: List<Pair<Int, String>>,
    selectedKey: Int?,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.find { it.first == selectedKey }?.second ?: (if (selectedKey != null) "Opción $selectedKey" else "Seleccionar...")

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Color.LightGray, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = currentLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color(0xFF444444),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF1E1E1E),
                    unfocusedContainerColor = Color(0xFF1E1E1E)
                ),
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                    .fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color(0xFF2C2C2C))
            ) {
                options.forEach { (key, title) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = title,
                                color = if (key == selectedKey) Color(0xFF00E5FF) else Color.White,
                                fontWeight = if (key == selectedKey) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onSelected(key)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
