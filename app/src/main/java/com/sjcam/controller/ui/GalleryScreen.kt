package com.sjcam.controller.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.sjcam.controller.data.CameraMediaItem
import com.sjcam.controller.data.MediaFilter
import com.sjcam.controller.viewmodel.CameraViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(viewModel: CameraViewModel) {
    val mediaItems by viewModel.mediaItems.collectAsState()
    val isScanning by viewModel.isScanningMedia.collectAsState()
    val activeFilter by viewModel.mediaFilter.collectAsState()
    val selectedVideo by viewModel.selectedMediaToPlay.collectAsState()
    val selectedPhoto by viewModel.selectedPhotoToView.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val downloadedFiles by viewModel.downloadedFiles.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedRelPaths by remember { mutableStateOf(setOf<String>()) }
    var itemPendingDelete by remember { mutableStateOf<CameraMediaItem?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteStatusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (mediaItems.isEmpty()) {
            viewModel.refreshMediaList()
        }
    }

    val filteredItems = remember(mediaItems, activeFilter) {
        when (activeFilter) {
            MediaFilter.ALL -> mediaItems
            MediaFilter.VIDEOS -> mediaItems.filter { it.isVideo }
            MediaFilter.PHOTOS -> mediaItems.filter { !it.isVideo }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141414))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Encabezado (Normal o Modo Selección)
        if (isSelectionMode) {
            Surface(
                color = Color(0xFF222222),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                isSelectionMode = false
                                selectedRelPaths = emptySet()
                            }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancelar selección", tint = Color.White)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${selectedRelPaths.size} seleccionados",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Seleccionar Todo / Nada
                        TextButton(
                            onClick = {
                                if (selectedRelPaths.size == filteredItems.size) {
                                    selectedRelPaths = emptySet()
                                } else {
                                    selectedRelPaths = filteredItems.map { it.relativePath }.toSet()
                                }
                            }
                        ) {
                            Text(
                                text = if (selectedRelPaths.size == filteredItems.size) "Deseleccionar" else "Todo",
                                color = Color(0xFF00E5FF),
                                fontSize = 12.sp
                            )
                        }

                        // Botón Eliminar Seleccionados
                        IconButton(
                            onClick = { showBatchDeleteConfirm = true },
                            enabled = selectedRelPaths.isNotEmpty() && !isDeleting,
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = Color(0xFFFF5252),
                                disabledContentColor = Color.DarkGray
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar seleccionados")
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Galería MicroSD",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Archivos multimedia en la cámara",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mediaItems.isNotEmpty()) {
                        TextButton(
                            onClick = { isSelectionMode = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF00E5FF))
                        ) {
                            Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Seleccionar", fontSize = 12.sp)
                        }
                    }

                    IconButton(
                        onClick = { viewModel.refreshMediaList() },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFF00E5FF))
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF00E5FF)
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Escanear MicroSD")
                        }
                    }
                }
            }
        }

        if (deleteStatusMessage != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = Color(0xFF2C2222),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFFFF8A80)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = deleteStatusMessage ?: "",
                        color = Color(0xFFFF8A80),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Filtros (Chips)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val videoCount = mediaItems.count { it.isVideo }
            val photoCount = mediaItems.count { !it.isVideo }

            FilterChip(
                selected = activeFilter == MediaFilter.ALL,
                onClick = { viewModel.setMediaFilter(MediaFilter.ALL) },
                label = { Text("Todos (${mediaItems.size})", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF00E5FF),
                    selectedLabelColor = Color.Black
                )
            )

            FilterChip(
                selected = activeFilter == MediaFilter.VIDEOS,
                onClick = { viewModel.setMediaFilter(MediaFilter.VIDEOS) },
                label = { Text("Videos ($videoCount)", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF00E5FF),
                    selectedLabelColor = Color.Black
                )
            )

            FilterChip(
                selected = activeFilter == MediaFilter.PHOTOS,
                onClick = { viewModel.setMediaFilter(MediaFilter.PHOTOS) },
                label = { Text("Fotos ($photoCount)", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF00E5FF),
                    selectedLabelColor = Color.Black
                )
            )
        }

        Spacer(Modifier.height(10.dp))

        // Lista de archivos o vista vacía
        if (isScanning && mediaItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = Color(0xFF00E5FF))
                    Text(
                        text = "Escaneando MicroSD en http://192.168.1.254...",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                }
            }
        } else if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No se encontraron archivos en la MicroSD",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Asegúrate de estar conectado al Wi-Fi de la cámara y presiona 'Escanear MicroSD'.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Button(
                        onClick = { viewModel.refreshMediaList() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Escanear MicroSD")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredItems, key = { it.relativePath }) { item ->
                    val isDownloaded = downloadedFiles.containsKey(item.name) || downloadedFiles.containsKey(item.relativePath)
                    val localPath = downloadedFiles[item.name] ?: downloadedFiles[item.relativePath]
                    val progress = downloadProgress[item.relativePath]
                    val isSelected = item.relativePath in selectedRelPaths

                    MediaItemCard(
                        item = item,
                        isDownloaded = isDownloaded,
                        downloadProgress = progress,
                        isSelectionMode = isSelectionMode,
                        isSelected = isSelected,
                        onToggleSelect = {
                            selectedRelPaths = if (isSelected) {
                                selectedRelPaths - item.relativePath
                            } else {
                                selectedRelPaths + item.relativePath
                            }
                        },
                        onPlayOrView = {
                            if (isSelectionMode) {
                                selectedRelPaths = if (isSelected) {
                                    selectedRelPaths - item.relativePath
                                } else {
                                    selectedRelPaths + item.relativePath
                                }
                            } else {
                                if (item.isVideo) {
                                    viewModel.playVideo(item)
                                } else {
                                    viewModel.viewPhoto(item)
                                }
                            }
                        },
                        onDownload = { viewModel.downloadMedia(item) },
                        onOpenLocal = {
                            if (localPath != null) {
                                openLocalFile(context, File(localPath), item.isVideo)
                            }
                        },
                        onDelete = {
                            itemPendingDelete = item
                        }
                    )
                }
            }
        }
    }

    // Cuadro de diálogo de confirmación para eliminar archivo individual
    itemPendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemPendingDelete = null },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFFF5252)) },
            title = { Text("¿Eliminar archivo?") },
            text = {
                Text(
                    "¿Estás seguro de que deseas eliminar permanentemente \"${item.name}\" (${item.formattedSize}) de la tarjeta MicroSD de la cámara y del teléfono?",
                    fontSize = 13.sp,
                    color = Color.LightGray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = item
                        itemPendingDelete = null
                        coroutineScope.launch {
                            isDeleting = true
                            deleteStatusMessage = "Eliminando ${toDelete.name}..."
                            val ok = viewModel.deleteMediaItem(toDelete)
                            isDeleting = false
                            deleteStatusMessage = if (ok) "Archivo eliminado." else "Aviso: Error eliminando archivo de la MicroSD."
                            kotlinx.coroutines.delay(2500)
                            deleteStatusMessage = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) {
                    Text("Eliminar", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemPendingDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Cuadro de diálogo de confirmación para eliminación múltiple
    if (showBatchDeleteConfirm) {
        val selectedItemsList = remember(selectedRelPaths, filteredItems) {
            filteredItems.filter { it.relativePath in selectedRelPaths }
        }
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFFF5252)) },
            title = { Text("¿Eliminar ${selectedItemsList.size} archivos?") },
            text = {
                Text(
                    "Esta acción borrará definitivamente los ${selectedItemsList.size} archivos seleccionados de la tarjeta MicroSD de la cámara y del almacenamiento local del teléfono.",
                    fontSize = 13.sp,
                    color = Color.LightGray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBatchDeleteConfirm = false
                        coroutineScope.launch {
                            isDeleting = true
                            deleteStatusMessage = "Eliminando ${selectedItemsList.size} archivos..."
                            val (okCount, failCount) = viewModel.deleteMultipleMediaItems(selectedItemsList)
                            isDeleting = false
                            isSelectionMode = false
                            selectedRelPaths = emptySet()
                            deleteStatusMessage = "Se eliminaron $okCount archivos" + if (failCount > 0) " ($failCount con error)" else "."
                            kotlinx.coroutines.delay(3000)
                            deleteStatusMessage = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) {
                    Text("Eliminar (${selectedItemsList.size})", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Modal de Reproducción de Video
    selectedVideo?.let { item ->
        VideoPlayerModal(
            item = item,
            viewModel = viewModel,
            onDismiss = { viewModel.closeMediaViewer() }
        )
    }

    // Modal de Visualización de Foto con navegación deslizante
    val photoList = remember(mediaItems) { mediaItems.filter { !it.isVideo } }
    selectedPhoto?.let { item ->
        val initialIndex = remember(item, photoList) {
            val idx = photoList.indexOfFirst { it.relativePath == item.relativePath }
            if (idx >= 0) idx else 0
        }
        PhotoViewerModal(
            photos = photoList,
            initialIndex = initialIndex,
            viewModel = viewModel,
            onDismiss = { viewModel.closeMediaViewer() },
            onDownload = { photoToDownload -> viewModel.downloadMedia(photoToDownload) }
        )
    }
}

@Composable
fun MediaItemCard(
    item: CameraMediaItem,
    isDownloaded: Boolean,
    downloadProgress: Float?,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onPlayOrView: () -> Unit,
    onDownload: () -> Unit,
    onOpenLocal: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = if (isSelected) Color(0xFF1E3545) else Color(0xFF1E1E1E),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isSelectionMode) onToggleSelect() else onPlayOrView()
            }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Checkbox de selección si estamos en modo selección
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF00E5FF),
                            checkmarkColor = Color.Black
                        )
                    )
                }

                // Ícono del tipo de archivo
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (item.isVideo) Color(0xFF1A3644) else Color(0xFF3E2723))
                        .clickable { onPlayOrView() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.isVideo) Icons.Default.PlayArrow else Icons.Default.Image,
                        contentDescription = null,
                        tint = if (item.isVideo) Color(0xFF00E5FF) else Color(0xFFFFB74D),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Info del archivo
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.formattedSize,
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                        if (item.dateTimeStr.isNotBlank()) {
                            Text(
                                text = "• ${item.dateTimeStr}",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // Botones de acción (visibles cuando no estamos seleccionando)
                if (!isSelectionMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Botón Reproducir / Ver
                        IconButton(
                            onClick = onPlayOrView,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFF00E5FF))
                        ) {
                            Icon(
                                if (item.isVideo) Icons.Default.PlayCircle else Icons.Default.Visibility,
                                contentDescription = if (item.isVideo) "Reproducir" else "Ver Foto"
                            )
                        }

                        // Botón Descargar / Abrir
                        if (isDownloaded) {
                            IconButton(
                                onClick = onOpenLocal,
                                colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFF00E676))
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Descargado (Abrir)")
                            }
                        } else if (downloadProgress != null && downloadProgress in 0f..0.99f) {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = Color(0xFF00E5FF)
                            )
                        } else {
                            IconButton(
                                onClick = onDownload,
                                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.LightGray)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Descargar al teléfono")
                            }
                        }

                        // Botón Eliminar
                        IconButton(
                            onClick = onDelete,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFFFF5252))
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar archivo")
                        }
                    }
                }
            }

            // Barra de progreso si está descargando
            if (downloadProgress != null && downloadProgress in 0.01f..0.99f) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Color(0xFF00E5FF),
                    trackColor = Color(0xFF333333)
                )
            }
        }
    }
}

@Composable
fun VideoPlayerModal(
    item: CameraMediaItem,
    viewModel: CameraViewModel,
    onDismiss: () -> Unit
) {
    val downloadedFiles by viewModel.downloadedFiles.collectAsState()
    val localPath = downloadedFiles[item.name] ?: downloadedFiles[item.relativePath]
    val isDownloaded = localPath != null && File(localPath).exists() && File(localPath).length() > 0

    val isPlaying by viewModel.playerManager.isPlaying.collectAsState()
    val mediaTime by viewModel.playerManager.mediaTime.collectAsState()
    val mediaLength by viewModel.playerManager.mediaLength.collectAsState()
    val mediaPosition by viewModel.playerManager.mediaPosition.collectAsState()

    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(0f) }
    var showControls by remember { mutableStateOf(true) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val videoPlayUrl = remember(item, isDownloaded, localPath) {
        if (isDownloaded && localPath != null) {
            Uri.fromFile(File(localPath)).toString()
        } else {
            item.httpUrl
        }
    }

    DisposableEffect(videoPlayUrl) {
        viewModel.playerManager.playMediaFile(videoPlayUrl)
        onDispose {
            viewModel.playerManager.stopStream()
            viewModel.playerManager.detachLayout()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { showControls = !showControls }
        ) {
            // Reproductor VLC embebido
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).apply {
                        viewModel.playerManager.attachLayout(this)
                    }
                },
                update = { layout ->
                    viewModel.playerManager.attachLayout(layout)
                },
                modifier = Modifier.fillMaxSize()
            )

            // Controles de Navegación y Barra de Tiempo
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Barra superior flotante
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(Color(0xB3000000))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isDownloaded) "Archivo descargado localmente"
                                else "Streaming directo MicroSD (${item.formattedSize})",
                                color = Color(0xFF00E5FF),
                                fontSize = 11.sp
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isDownloaded) {
                                IconButton(
                                    onClick = { viewModel.downloadMedia(item) },
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "Descargar")
                                }
                            }

                            // Botón Eliminar Video
                            IconButton(
                                onClick = { showDeleteConfirm = true },
                                colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFFFF5252))
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Eliminar Video")
                            }

                            IconButton(
                                onClick = onDismiss,
                                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cerrar")
                            }
                        }
                    }

                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFFF5252)) },
                            title = { Text("¿Eliminar video?") },
                            text = { Text("¿Deseas eliminar permanentemente \"${item.name}\" (${item.formattedSize}) de la cámara?") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showDeleteConfirm = false
                                        coroutineScope.launch {
                                            viewModel.deleteMediaItem(item)
                                            onDismiss()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                                ) {
                                    Text("Eliminar", color = Color.White)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteConfirm = false }) {
                                    Text("Cancelar")
                                }
                            }
                        )
                    }

                    // Botón central flotante de Play/Pausa rápido
                    IconButton(
                        onClick = { viewModel.playerManager.togglePlayPause() },
                        modifier = Modifier
                            .size(64.dp)
                            .align(Alignment.Center)
                            .background(Color(0x80000000), androidx.compose.foundation.shape.CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Barra inferior de navegación / Barra de progreso SeekBar
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color(0xB3000000))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        // Slider / Barra de progreso interactiva
                        val currentSliderPos = if (isDragging) dragPosition else mediaPosition
                        Slider(
                            value = currentSliderPos.coerceIn(0f, 1f),
                            onValueChange = {
                                isDragging = true
                                dragPosition = it
                            },
                            onValueChangeFinished = {
                                viewModel.playerManager.seekToPosition(dragPosition)
                                isDragging = false
                            },
                            modifier = Modifier.fillMaxWidth().height(24.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E5FF),
                                activeTrackColor = Color(0xFF00E5FF),
                                inactiveTrackColor = Color(0x55FFFFFF)
                            )
                        )

                        Spacer(Modifier.height(4.dp))

                        // Tiempos y botones de salto ±10s
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTimeMs(mediaTime),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )

                            // Controles de transporte
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                IconButton(
                                    onClick = { viewModel.playerManager.jumpSeconds(-10) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Replay10,
                                        contentDescription = "Retroceder 10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { viewModel.playerManager.togglePlayPause() },
                                    modifier = Modifier
                                        .size(42.dp)
                                        .background(Color(0xFF00E5FF), androidx.compose.foundation.shape.CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                        tint = Color.Black,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { viewModel.playerManager.jumpSeconds(10) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Forward10,
                                        contentDescription = "Adelantar 10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Text(
                                text = if (mediaLength > 0) formatTimeMs(mediaLength) else "--:--",
                                color = Color.LightGray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoViewerModal(
    photos: List<CameraMediaItem>,
    initialIndex: Int,
    viewModel: CameraViewModel,
    onDismiss: () -> Unit,
    onDownload: (CameraMediaItem) -> Unit
) {
    if (photos.isEmpty()) {
        onDismiss()
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, photos.size - 1),
        pageCount = { photos.size }
    )
    val coroutineScope = rememberCoroutineScope()
    val currentPhoto = photos.getOrNull(pagerState.currentPage) ?: photos.first()

    val downloadedFiles by viewModel.downloadedFiles.collectAsState()
    val isCurrentDownloaded = (downloadedFiles[currentPhoto.name] != null || downloadedFiles[currentPhoto.relativePath] != null)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF2000000))
        ) {
            // Carrusel deslizable táctil (Swipe)
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val photo = photos[page]
                SinglePhotoPage(photo = photo, viewModel = viewModel)
            }

            // Flecha flotante Anterior
            if (pagerState.currentPage > 0) {
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 10.dp)
                        .size(44.dp)
                        .background(Color(0x80000000), androidx.compose.foundation.shape.CircleShape)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBackIos,
                        contentDescription = "Foto Anterior",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp).offset(x = 2.dp)
                    )
                }
            }

            // Flecha flotante Siguiente
            if (pagerState.currentPage < photos.size - 1) {
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 10.dp)
                        .size(44.dp)
                        .background(Color(0x80000000), androidx.compose.foundation.shape.CircleShape)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = "Foto Siguiente",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Barra superior flotante con contador y descarga
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color(0x99000000))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentPhoto.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${pagerState.currentPage + 1} de ${photos.size}  •  ${currentPhoto.formattedSize}",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isCurrentDownloaded) {
                        IconButton(
                            onClick = { onDownload(currentPhoto) },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Descargar Foto")
                        }
                    }

                    // Botón Eliminar Foto
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFFFF5252))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Eliminar Foto")
                    }

                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFFF5252)) },
                            title = { Text("¿Eliminar foto?") },
                            text = { Text("¿Deseas eliminar permanentemente \"${currentPhoto.name}\" (${currentPhoto.formattedSize}) de la cámara?") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showDeleteConfirm = false
                                        val toDel = currentPhoto
                                        coroutineScope.launch {
                                            viewModel.deleteMediaItem(toDel)
                                            if (photos.size <= 1) {
                                                onDismiss()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                                ) {
                                    Text("Eliminar", color = Color.White)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteConfirm = false }) {
                                    Text("Cancelar")
                                }
                            }
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }
            }
        }
    }
}

@Composable
fun SinglePhotoPage(
    photo: CameraMediaItem,
    viewModel: CameraViewModel
) {
    var imageBitmap by remember(photo.relativePath) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(photo.relativePath) { mutableStateOf(true) }
    var statusText by remember(photo.relativePath) { mutableStateOf("Cargando foto...") }

    val downloadedFiles by viewModel.downloadedFiles.collectAsState()
    val localPath = downloadedFiles[photo.name] ?: downloadedFiles[photo.relativePath]
    val isDownloaded = localPath != null && File(localPath).exists() && File(localPath).length() > 0

    LaunchedEffect(photo.relativePath, isDownloaded) {
        isLoading = true
        withContext(Dispatchers.IO) {
            // 1. Cargar archivo local si ya se descargó
            if (isDownloaded && localPath != null) {
                try {
                    statusText = "Cargando foto desde almacenamiento local..."
                    val bmp = decodeSampledBitmap(File(localPath).absolutePath, 2048, 2048)
                    if (bmp != null) {
                        imageBitmap = bmp.asImageBitmap()
                        isLoading = false
                        return@withContext
                    }
                } catch (e: Exception) {
                    com.sjcam.controller.data.AppLogger.w("PhotoViewer", "Fallo cargando foto local: ${e.message}")
                }
            }

            // 2. Probar candidatos HTTP desde la cámara
            val candidates = photo.getCandidateUrls(viewModel.apiClient.cameraBaseUrl)
            val client = OkHttpClient.Builder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            var success = false
            for (url in candidates) {
                statusText = "Descargando:\n$url"
                try {
                    val request = Request.Builder().url(url).get().build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                val bmp = decodeSampledBitmapFromBytes(bytes, 2048, 2048)
                                if (bmp != null) {
                                    imageBitmap = bmp.asImageBitmap()
                                    success = true
                                    return@use
                                }
                            }
                        }
                    }
                    if (success) break
                } catch (_: Exception) {}
            }

            if (!success) {
                statusText = "No se pudo cargar la imagen directamente desde la cámara."
            }
            isLoading = false
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFF00E5FF))
                Text(
                    text = statusText,
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else if (imageBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = imageBitmap!!,
                contentDescription = photo.name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 48.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(Icons.Default.BrokenImage, contentDescription = null, tint = Color.Red, modifier = Modifier.size(48.dp))
                Text(
                    text = statusText,
                    color = Color(0xFFFF8A80),
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

private fun formatTimeMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hr = totalSec / 3600
    return if (hr > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hr, min, sec)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", min, sec)
    }
}

private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        BitmapFactory.decodeFile(path, options)
    } catch (_: Exception) {
        null
    }
}

private fun decodeSampledBitmapFromBytes(bytes: ByteArray, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (_: Exception) {
        null
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun openLocalFile(context: android.content.Context, file: File, isVideo: Boolean) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val mime = if (isVideo) "video/mp4" else "image/jpeg"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                type = if (isVideo) "video/mp4" else "image/jpeg"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Abrir archivo con...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {}
    }
}
