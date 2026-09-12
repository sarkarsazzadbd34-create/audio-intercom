package com.example.audiotx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.audiotx.ui.TransmitterScreen

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result observed via hasRequiredPermissions() on next recomposition */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TransmitterScreen(
                hasPermissions = { hasRequiredPermissions() },
                onRequestPermissions = { requestPermissions.launch(requiredPermissions()) },
                onStartLan = {
                    startService(Intent(this, TransmitterService::class.java).setAction(TransmitterService.ACTION_START_LAN))
                },
                onStartInternet = { url ->
                    startService(
                        Intent(this, TransmitterService::class.java)
                            .setAction(TransmitterService.ACTION_START_INTERNET)
                            .putExtra(TransmitterService.EXTRA_SIGNALING_URL, url)
                    )
                },
                onStop = {
                    startService(Intent(this, TransmitterService::class.java).setAction(TransmitterService.ACTION_STOP))
                }
            )
        }
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}
