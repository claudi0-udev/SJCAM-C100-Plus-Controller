package com.sjcam.controller

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.sjcam.controller.data.AppLogger
import com.sjcam.controller.ui.CommandTesterScreen
import com.sjcam.controller.ui.LiveControlScreen
import com.sjcam.controller.ui.LogViewerScreen
import com.sjcam.controller.ui.SettingsScreen
import com.sjcam.controller.ui.theme.SjcamTheme
import com.sjcam.controller.viewmodel.CameraViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            AppLogger.i("MainActivity", "Todos los permisos de red concedidos.")
        } else {
            AppLogger.w("MainActivity", "Algunos permisos fueron denegados.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLogger.i("MainActivity", "Iniciando SJCAM C100+ Controller...")
        checkAndRequestPermissions()

        setContent {
            SjcamTheme {
                MainAppScaffold(viewModel)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }
}

enum class AppTab(val label: String) {
    CAMERA("Cámara"),
    SETTINGS("Ajustes"),
    COMMANDS("Comandos"),
    LOGS("Logs / Debug")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(viewModel: CameraViewModel) {
    var selectedTab by remember { mutableStateOf(AppTab.CAMERA) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF1E1E1E)
            ) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.CAMERA,
                    onClick = { selectedTab = AppTab.CAMERA },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = "Cámara") },
                    label = { Text(AppTab.CAMERA.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF00E5FF),
                        selectedTextColor = Color(0xFF00E5FF),
                        indicatorColor = Color(0xFF2C2C2C),
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == AppTab.SETTINGS,
                    onClick = { selectedTab = AppTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
                    label = { Text(AppTab.SETTINGS.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF00E5FF),
                        selectedTextColor = Color(0xFF00E5FF),
                        indicatorColor = Color(0xFF2C2C2C),
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == AppTab.COMMANDS,
                    onClick = { selectedTab = AppTab.COMMANDS },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Comandos") },
                    label = { Text(AppTab.COMMANDS.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF00E5FF),
                        selectedTextColor = Color(0xFF00E5FF),
                        indicatorColor = Color(0xFF2C2C2C),
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == AppTab.LOGS,
                    onClick = { selectedTab = AppTab.LOGS },
                    icon = { Icon(Icons.Default.BugReport, contentDescription = "Logs") },
                    label = { Text(AppTab.LOGS.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF00E5FF),
                        selectedTextColor = Color(0xFF00E5FF),
                        indicatorColor = Color(0xFF2C2C2C),
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                AppTab.CAMERA -> LiveControlScreen(viewModel)
                AppTab.SETTINGS -> SettingsScreen(viewModel)
                AppTab.COMMANDS -> CommandTesterScreen(viewModel)
                AppTab.LOGS -> LogViewerScreen()
            }
        }
    }
}
