package com.example.audiotx.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.example.audiotx.PairingManager
import com.example.audiotx.PairingState
import kotlinx.coroutines.delay

private enum class Mode { LAN, INTERNET }

@Composable
fun TransmitterScreen(
    hasPermissions: () -> Boolean,
    onRequestPermissions: () -> Unit,
    onStartLan: () -> Unit,
    onStartInternet: (String) -> Unit,
    onStop: () -> Unit
) {
    var mode by remember { mutableStateOf(Mode.LAN) }
    var signalingUrl by remember { mutableStateOf("wss://your-server.example/ws") }
    var isStreaming by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Idle") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var permissionsGranted by remember { mutableStateOf(hasPermissions()) }

    // Poll the tiny in-memory bridge for status/QR updates from the service.
    LaunchedEffect(Unit) {
        while (true) {
            status = PairingState.status
            PairingState.currentPayload?.let { qrBitmap = PairingManager.toQrBitmap(it) }
            permissionsGranted = hasPermissions()
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Audio Transmitter", style = MaterialTheme.typography.headlineSmall)

        if (!permissionsGranted) {
            Text("Microphone permission is required before you can stream.")
            Button(onClick = onRequestPermissions) { Text("Grant permissions") }
            return@Column
        }

        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = mode == Mode.LAN,
                onClick = { mode = Mode.LAN },
                shape = SegmentedButtonDefaults.itemShape(0, 2)
            ) { Text("Same Wi-Fi") }
            SegmentedButton(
                selected = mode == Mode.INTERNET,
                onClick = { mode = Mode.INTERNET },
                shape = SegmentedButtonDefaults.itemShape(1, 2)
            ) { Text("Internet") }
        }

        if (mode == Mode.INTERNET) {
            OutlinedTextField(
                value = signalingUrl,
                onValueChange = { signalingUrl = it },
                label = { Text("Signaling server URL (wss://...)") },
                enabled = !isStreaming,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text("Status: $status", style = MaterialTheme.typography.bodyMedium)

        qrBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Pairing QR code",
                modifier = Modifier.size(220.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                enabled = !isStreaming,
                onClick = {
                    isStreaming = true
                    if (mode == Mode.LAN) onStartLan() else onStartInternet(signalingUrl)
                }
            ) { Text("START") }

            Button(
                enabled = isStreaming,
                onClick = {
                    isStreaming = false
                    qrBitmap = null
                    onStop()
                }
            ) { Text("STOP") }
        }

        Text(
            "While streaming, a persistent notification is shown and cannot be hidden. " +
                "Audio only ever goes to the single paired receiver above.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
