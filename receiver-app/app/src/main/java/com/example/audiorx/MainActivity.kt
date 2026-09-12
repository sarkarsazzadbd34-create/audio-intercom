package com.example.audiorx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.audiorx.ui.ReceiverScreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {

    private val json = Json { ignoreUnknownKeys = true }
    private var onQrResult: ((PairingPayload?) -> Unit)? = null

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        val payload = raw?.let {
            try { json.decodeFromString(PairingPayload.serializer(), it) } catch (e: Exception) { null }
        }
        onQrResult?.invoke(payload)
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* re-checked via hasRequiredPermissions() */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ReceiverScreen(
                hasPermissions = { hasRequiredPermissions() },
                onRequestPermissions = { requestPermissions.launch(requiredPermissions()) },
                onScanQr = { callback ->
                    onQrResult = callback
                    qrScanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the Transmitter's pairing QR code")
                            .setBeepEnabled(false)
                    )
                },
                onDiscoverLan = { onFound, onLost ->
                    val discovery = NsdDiscovery(this)
                    discovery.start(onFound, onLost)
                    discovery // caller keeps a handle to call .stop()
                },
                onConnectLan = { code, host, port ->
                    startService(
                        Intent(this, ReceiverService::class.java)
                            .setAction(ReceiverService.ACTION_CONNECT)
                            .putExtra(ReceiverService.EXTRA_MODE, "lan")
                            .putExtra(ReceiverService.EXTRA_CODE, code)
                            .putExtra(ReceiverService.EXTRA_HOST, host)
                            .putExtra(ReceiverService.EXTRA_PORT, port)
                    )
                },
                onConnectInternet = { code, signalingUrl ->
                    startService(
                        Intent(this, ReceiverService::class.java)
                            .setAction(ReceiverService.ACTION_CONNECT)
                            .putExtra(ReceiverService.EXTRA_MODE, "internet")
                            .putExtra(ReceiverService.EXTRA_CODE, code)
                            .putExtra(ReceiverService.EXTRA_SIGNALING_URL, signalingUrl)
                    )
                },
                onPlay = {
                    startService(Intent(this, ReceiverService::class.java).setAction(ReceiverService.ACTION_PLAY))
                },
                onStop = {
                    startService(Intent(this, ReceiverService::class.java).setAction(ReceiverService.ACTION_STOP))
                },
                onDisconnect = {
                    startService(Intent(this, ReceiverService::class.java).setAction(ReceiverService.ACTION_DISCONNECT))
                },
                onToggleSpeaker = { on ->
                    startService(
                        Intent(this, ReceiverService::class.java)
                            .setAction(ReceiverService.ACTION_TOGGLE_SPEAKER)
                            .putExtra(ReceiverService.EXTRA_SPEAKER_ON, on)
                    )
                }
            )
        }
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return perms.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}
