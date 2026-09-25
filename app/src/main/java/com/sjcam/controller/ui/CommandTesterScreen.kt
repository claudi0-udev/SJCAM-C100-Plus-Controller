package com.sjcam.controller.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
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
import com.sjcam.controller.viewmodel.CameraViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandTesterScreen(viewModel: CameraViewModel) {
    val context = LocalContext.current
    var cmdText by remember { mutableStateOf("3019") }
    var parText by remember { mutableStateOf("") }
    val resultText by viewModel.rawCommandResult.collectAsState()

    val quickCommands = listOf(
        Triple("Batería (3019)", "3019", ""),
        Triple("Espacio SD (3017)", "3017", ""),
        Triple("Lista Archivos (3015)", "3015", ""),
        Triple("Info/Ver (3025)", "3025", ""),
        Triple("Foto (1001)", "1001", ""),
        Triple("Rec Start (2001)", "2001", "1"),
        Triple("Rec Stop (2001)", "2001", "0"),
        Triple("Live ON (2015)", "2015", "1"),
        Triple("Live OFF (2015)", "2015", "0")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Tester de Comandos Novatek",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = Color.White
        )
        Text(
            text = "Prueba comandos directos a http://192.168.1.254/?custom=1&cmd=... para explorar la cámara.",
            fontSize = 12.sp,
            color = Color.Gray
        )

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { viewModel.runFullDiagnostics() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Diagnóstico")
            Spacer(Modifier.width(8.dp))
            Text("🔍 Ejecutar Auto-Diagnóstico de Stream & Puertos")
        }

        Spacer(Modifier.height(16.dp))

        // Accesos rápidos
        Text("Comandos Predefinidos:", color = Color.LightGray, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickCommands.forEach { (label, cmd, par) ->
                SuggestionChip(
                    onClick = {
                        cmdText = cmd
                        parText = par
                        viewModel.sendRawCommand(cmd, par)
                    },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Inputs manuales
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = cmdText,
                onValueChange = { cmdText = it },
                label = { Text("cmd (ej: 2001)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            OutlinedTextField(
                value = parText,
                onValueChange = { parText = it },
                label = { Text("par (opcional)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { viewModel.sendRawCommand(cmdText, parText) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
        ) {
            Icon(Icons.Default.Send, contentDescription = "Enviar")
            Spacer(Modifier.width(8.dp))
            Text("Enviar Comando a la Cámara")
        }

        Spacer(Modifier.height(20.dp))

        // Visor de respuesta
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Respuesta de la Cámara:", fontWeight = FontWeight.SemiBold, color = Color.White)

            if (resultText.isNotBlank()) {
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("SJCAM Response", resultText))
                        Toast.makeText(context, "Respuesta copiada", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copiar Respuesta", tint = Color(0xFF00E5FF))
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 150.dp)
                .background(Color(0xFF101010), RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            if (resultText.isBlank()) {
                Text(
                    text = "Presiona 'Enviar Comando' para ver el XML devuelto por la C100+.",
                    color = Color.DarkGray,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                Text(
                    text = resultText,
                    color = Color(0xFF69F0AE),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
