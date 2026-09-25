package com.sjcam.controller.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sjcam.controller.data.AppLogger
import com.sjcam.controller.data.LogEntry
import com.sjcam.controller.data.LogLevel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen() {
    val context = LocalContext.current
    val logs by AppLogger.logsState.collectAsState()
    var selectedFilter by remember { mutableStateOf<LogLevel?>(null) }
    var filterQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Filtrar logs según selección
    val filteredLogs = remember(logs, selectedFilter, filterQuery) {
        logs.filter { entry ->
            val matchesLevel = selectedFilter == null || entry.level == selectedFilter
            val matchesQuery = filterQuery.isBlank() ||
                    entry.message.contains(filterQuery, ignoreCase = true) ||
                    entry.tag.contains(filterQuery, ignoreCase = true) ||
                    (entry.payload?.contains(filterQuery, ignoreCase = true) == true)
            matchesLevel && matchesQuery
        }
    }

    // Scroll automático al final cuando llega un nuevo log
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(12.dp)
    ) {
        // Barra superior con acciones rápidas
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Debug Console (${filteredLogs.size})",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Botón Copiar al portapapeles
                FilledTonalButton(
                    onClick = {
                        val text = AppLogger.exportFormattedLogs()
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("SJCAM Logs", text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "¡Logs copiados al portapapeles!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color.Black
                    )
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copiar", fontSize = 12.sp)
                }

                // Botón Compartir
                IconButton(
                    onClick = {
                        val text = AppLogger.exportFormattedLogs()
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, text)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, "Enviar logs de depuración")
                        context.startActivity(shareIntent)
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Compartir", tint = Color.White)
                }

                // Botón Limpiar
                IconButton(
                    onClick = { AppLogger.clear() }
                ) {
                    Icon(Icons.Default.Clear, contentDescription = "Limpiar", tint = Color(0xFFFF5252))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Filtros por Categoría
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { selectedFilter = null },
                label = { Text("TODOS", fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == LogLevel.HTTP,
                onClick = { selectedFilter = if (selectedFilter == LogLevel.HTTP) null else LogLevel.HTTP },
                label = { Text("HTTP", fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == LogLevel.RTSP,
                onClick = { selectedFilter = if (selectedFilter == LogLevel.RTSP) null else LogLevel.RTSP },
                label = { Text("RTSP", fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == LogLevel.ERROR,
                onClick = { selectedFilter = if (selectedFilter == LogLevel.ERROR) null else LogLevel.ERROR },
                label = { Text("ERROR", fontSize = 11.sp) }
            )
        }

        Spacer(Modifier.height(8.dp))

        // Lista de Logs
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF101010), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            if (filteredLogs.isEmpty()) {
                Text(
                    text = "No hay registros disponibles aún. Las peticiones y eventos aparecerán aquí.",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredLogs, key = { it.id }) { item ->
                        LogItemView(item)
                    }
                }
            }
        }
    }
}

@Composable
fun LogItemView(entry: LogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.ERROR -> Color(0xFFFF5252)
        LogLevel.WARN -> Color(0xFFFFB74D)
        LogLevel.HTTP -> Color(0xFF40C4FF)
        LogLevel.RTSP -> Color(0xFFB388FF)
        LogLevel.INFO -> Color(0xFF69F0AE)
        LogLevel.DEBUG -> Color(0xFFB0BEC5)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.formattedTime(),
                color = Color.DarkGray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "[${entry.level.name}]",
                color = levelColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.tag,
                color = Color(0xFFAAAAAA),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Text(
            text = entry.message,
            color = Color(0xFFE0E0E0),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )

        if (!entry.payload.isNullOrBlank()) {
            Surface(
                color = Color(0xFF242424),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            ) {
                Text(
                    text = entry.payload,
                    color = Color(0xFF81D4FA),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(6.dp)
                )
            }
        }
    }
}
