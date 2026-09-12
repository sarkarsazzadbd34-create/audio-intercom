package com.example.audiorx.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.audiorx.DiscoveredTransmitter
import com.example.audiorx.NsdDiscovery
import com.example.audiorx.PairingPayload

private enum class Mode { LAN, INTERNET }
private enum class ConnState { IDLE, CONNECTING, CONNECTED }

@Composable
fun ReceiverScreen(
    hasPermissions: () -> Boolean,
    onRequestPermissions: () -> Unit,
    onScanQr: ((PairingPayload?) -> Unit) -> Unit,
    onDiscoverLan: ((DiscoveredTransmitter) -> Unit, (String) -> Unit) -> NsdDiscovery,
    onConnectLan: (code: String, host: String, port: Int) -> Unit,
    onConnectInternet: (code: String, signalingUrl: String) -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleSpeaker: (Boolean) -> Unit
) {
    var mode by remember { mutableStateOf(Mode.LAN) }
    var connState by remember { mutableStateOf(ConnState.IDLE) }
    var manualCode by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("") }
    var signalingUrl by remember { mutableStateOf("wss://your-server.example/ws") }
    var speakerOn by remember { mutableStateOf(true) }
    var permissionsGranted by remember { mutableStateOf(hasPermissions()) }

    val discovered = remember { mutableStateListOf<DiscoveredTransmitter>() }
    var discovery by remember { mutableStateOf<NsdDiscovery?>(null) }

    DisposableEffect(mode) {
        if (mode == Mode.LAN) {
            discovery = onDiscoverLan(
                { found -> if (discovered.none { it.name == found.name }) discovered.add(found) },
                { lostName -> discovered.removeAll { it.name == lostName } }
            )
        }
        onDispose { discovery?.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Audio Receiver", style = MaterialTheme.typography.headlineSmall)

        if (!permissionsGranted) {
            Text("Camera (QR scan) and notification permissions are needed to pair.")
            Button(onClick = { onRequestPermissions(); permissionsGranted = hasPermissions() }) {
                Text("Grant permissions")
            }
            return@Column
        }

        if (connState == ConnState.IDLE) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = mode == Mode.LAN, onClick = { mode = Mode.LAN },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Same Wi-Fi") }
                SegmentedButton(
                    selected = mode == Mode.INTERNET, onClick = { mode = Mode.INTERNET },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Internet") }
            }

            Button(onClick = {
                onScanQr { payload ->
                    if (payload != null) {
                        if (payload.mode == "lan" && payload.host != null && payload.port != null) {
                            mode = Mode.LAN
                            connState = ConnState.CONNECTING
                            onConnectLan(payload.code, payload.host, payload.port)
                        } else if (payload.mode == "internet" && payload.signalingUrl != null) {
                            mode = Mode.INTERNET
                            connState = ConnState.CONNECTING
                            onConnectInternet(payload.code, payload.signalingUrl)
                        }
                    }
                }
            }) { Text("Scan QR code") }

            if (mode == Mode.LAN) {
                Text("Or pick a transmitter found on this Wi-Fi:", style = MaterialTheme.typography.bodySmall)
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(discovered) { t ->
                        ListItem(
                            headlineContent = { Text(t.name) },
                            supportingContent = { Text("${t.host}:${t.port}") },
                            modifier = Modifier.clickable {
                                connState = ConnState.CONNECTING
                                onConnectLan(manualCode.ifBlank { "" }, t.host, t.port)
                            }
                        )
                    }
                }
                OutlinedTextField(manualHost, { manualHost = it }, label = { Text("Or type host IP") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(manualPort, { manualPort = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(manualCode, { manualCode = it }, label = { Text("Pairing code") }, modifier = Modifier.fillMaxWidth())
                Button(
                    enabled = manualHost.isNotBlank() && manualPort.toIntOrNull() != null,
                    onClick = {
                        connState = ConnState.CONNECTING
                        onConnectLan(manualCode, manualHost, manualPort.toInt())
                    }
                ) { Text("Connect manually") }
            } else {
                OutlinedTextField(signalingUrl, { signalingUrl = it }, label = { Text("Signaling URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(manualCode, { manualCode = it }, label = { Text("Pairing code") }, modifier = Modifier.fillMaxWidth())
                Button(
                    enabled = manualCode.isNotBlank(),
                    onClick = {
                        connState = ConnState.CONNECTING
                        onConnectInternet(manualCode, signalingUrl)
                    }
                ) { Text("Connect") }
            }
        } else {
            Text(if (connState == ConnState.CONNECTING) "Connecting…" else "Connected")

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onPlay(); connState = ConnState.CONNECTED }) { Text("PLAY") }
                Button(onClick = onStop) { Text("STOP") }
                Button(onClick = { onDisconnect(); connState = ConnState.IDLE }) { Text("DISCONNECT") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speaker")
                Switch(checked = speakerOn, onCheckedChange = {
                    speakerOn = it
                    onToggleSpeaker(it)
                })
            }
        }

        Text(
            "Playback continues in the background via a foreground service; " +
                "the notification always shows PLAY/STOP/DISCONNECT while connected.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
