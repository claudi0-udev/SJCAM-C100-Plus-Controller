package com.sjcam.controller.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
        // Encabezado
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

                    MediaItemCard(
                        item = item,
                        isDownloaded = isDownloaded,
                        downloadProgress = progress,
                        onPlayOrView = {
                            if (item.isVideo) {
                                viewModel.playVideo(item)
                            } else {
                                viewModel.viewPhoto(item)
                            }
                        },
                        onDownload = { viewModel.downloadMedia(item) },
                        onOpenLocal = {
                            if (localPath != null) {
                                openLocalFile(context, File(localPath), item.isVideo)
                            }
                        }
                    )
                }
            }
        }
    }

    // Modal de Reproducción de Video
    selectedVideo?.let { item ->
        VideoPlayerModal(
            item = item,
            viewModel = viewModel,
            onDismiss = { viewModel.closeMediaViewer() }
        )
    }

    // Modal de Visualización de Foto
    selectedPhoto?.let { item ->
        PhotoViewerModal(
            item = item,
            onDismiss = { viewModel.closeMediaViewer() },
            onDownload = { viewModel.downloadMedia(item) }
        )
    }
}

@Composable
fun MediaItemCard(
    item: CameraMediaItem,
    isDownloaded: Boolean,
    downloadProgress: Float?,
    onPlayOrView: () -> Unit,
    onDownload: () -> Unit,
    onOpenLocal: () -> Unit
) {
    Surface(
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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

                // Botones de acción
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Reproductor VLC embebido
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).apply {
                        viewModel.playerManager.attachLayout(this)
                        viewModel.playerManager.startStream(item.httpUrl, forceTcp = true)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Barra superior flotante
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
                        text = item.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Streaming directo desde MicroSD (${item.formattedSize})",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { viewModel.downloadMedia(item) },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Descargar")
                    }

                    IconButton(
                        onClick = {
                            viewModel.playerManager.stopStream()
                            onDismiss()
                        },
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
fun PhotoViewerModal(
    item: CameraMediaItem,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.httpUrl) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(item.httpUrl).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            imageBitmap = bmp?.asImageBitmap()
                        }
                    } else {
                        errorMsg = "HTTP ${response.code}"
                    }
                }
            } catch (e: Exception) {
                errorMsg = e.message
            } finally {
                isLoading = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6000000))
        ) {
            // Imagen o indicador de carga
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFF00E5FF)
                )
            } else if (imageBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = imageBitmap!!,
                    contentDescription = item.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            } else {
                Text(
                    text = "No se pudo cargar la imagen: ${errorMsg ?: "Error desconocido"}",
                    color = Color.Red,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Barra superior
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color(0x99000000))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Row {
                    IconButton(onClick = onDownload, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                        Icon(Icons.Default.Download, contentDescription = "Descargar")
                    }
                    IconButton(onClick = onDismiss, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }
            }
        }
    }
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
        // Fallback: intentar abrir con chooser genérico
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
