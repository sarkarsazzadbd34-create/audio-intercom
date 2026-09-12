package com.example.audiorx

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.net.URI

/**
 * Runs the WebRTC playback session in the background. Uses
 * foregroundServiceType="mediaPlayback" so Android allows continued audio
 * playback after the user navigates away, and requires a persistent
 * notification with PLAY/STOP/DISCONNECT actions - never runs silently
 * without that notification.
 */
class ReceiverService : LifecycleService() {

    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null
    private var pairingCode: String = ""
    private var mode: String = "lan"

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CONNECT -> {
                mode = intent.getStringExtra(EXTRA_MODE) ?: "lan"
                pairingCode = intent.getStringExtra(EXTRA_CODE) ?: return START_NOT_STICKY
                val wsUrl = if (mode == "lan") {
                    val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                    val port = intent.getIntExtra(EXTRA_PORT, -1)
                    "ws://$host:$port"
                } else {
                    intent.getStringExtra(EXTRA_SIGNALING_URL) ?: return START_NOT_STICKY
                }
                connect(wsUrl)
            }
            ACTION_TOGGLE_SPEAKER -> {
                val on = intent.getBooleanExtra(EXTRA_SPEAKER_ON, true)
                webRtcClient?.setSpeakerphoneOn(on)
            }
            ACTION_PLAY -> {
                webRtcClient?.setPlaybackEnabled(true)
                updateNotification("Playing live audio")
            }
            ACTION_STOP -> {
                // Pause local playback only; the WebRTC session and signaling
                // stay connected so the user can resume with PLAY.
                webRtcClient?.setPlaybackEnabled(false)
                updateNotification("Paused")
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    private fun connect(wsUrl: String) {
        showForegroundNotification("Connecting…")

        signalingClient = SignalingClient(
            uri = URI(wsUrl),
            onOpenCb = {
                if (mode == "internet") {
                    signalingClient?.send(SignalingMessage(type = "join_room", code = pairingCode))
                }
                // LAN mode: opening the socket to the transmitter's embedded
                // server *is* the join; the transmitter starts the offer as
                // soon as it sees the connection.
                updateNotification("Waiting for audio…")
            },
            onMessageCb = { msg -> handleSignaling(msg) },
            onCloseCb = { reason -> updateNotification("Disconnected: $reason") }
        ).also { it.connect() }

        setupWebRtc()
    }

    private fun setupWebRtc() {
        webRtcClient = WebRtcClient(
            context = applicationContext,
            onLocalSdp = { sdp -> signalingClient?.send(SignalingMessage(
                type = "answer",
                code = pairingCode,
                payload = SdpOrCandidate(sdp = sdp.description, sdpType = "answer")
            )) },
            onLocalIceCandidate = { candidate -> signalingClient?.send(SignalingMessage(
                type = "candidate",
                code = pairingCode,
                payload = SdpOrCandidate(
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex
                )
            )) },
            onConnectionStateChange = { state ->
                serviceScope.launch {
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> updateNotification("Playing live audio")
                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.FAILED -> updateNotification("Connection lost")
                        else -> {}
                    }
                }
            }
        )
        val iceServers = if (mode == "internet") {
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            // Add your coturn TURN server credentials here too, matching the transmitter's config.
        } else emptyList()
        webRtcClient?.start(iceServers)
    }

    private fun handleSignaling(msg: SignalingMessage) {
        when (msg.type) {
            "offer" -> {
                val sdp = msg.payload?.sdp ?: return
                webRtcClient?.onRemoteOffer(SessionDescription(SessionDescription.Type.OFFER, sdp))
            }
            "candidate" -> {
                val p = msg.payload ?: return
                webRtcClient?.onRemoteIceCandidate(
                    IceCandidate(p.sdpMid, p.sdpMLineIndex ?: 0, p.candidate)
                )
            }
            "bye", "peer_left" -> updateNotification("Transmitter disconnected")
            "error" -> updateNotification("Signaling error: ${msg.message}")
        }
    }

    private fun disconnect() {
        signalingClient?.send(SignalingMessage(type = "bye", code = pairingCode))
        webRtcClient?.stop()
        webRtcClient = null
        signalingClient?.close()
        signalingClient = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun showForegroundNotification(text: String) {
        val channel = NotificationChannel(
            CHANNEL_ID, "Audio playback", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        postNotification(text)
    }

    private fun updateNotification(text: String) = postNotification(text)

    private fun postNotification(text: String) {
        val stopIntent = Intent(this, ReceiverService::class.java).setAction(ACTION_STOP)
        val disconnectIntent = Intent(this, ReceiverService::class.java).setAction(ACTION_DISCONNECT)
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val disconnectPending = PendingIntent.getService(this, 1, disconnectIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val playIntent = Intent(this, ReceiverService::class.java).setAction(ACTION_PLAY)
        val playPending = PendingIntent.getService(this, 2, playIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Receiver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .addAction(0, "PLAY", playPending)
            .addAction(0, "STOP", stopPending)
            .addAction(0, "DISCONNECT", disconnectPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        const val ACTION_CONNECT = "com.example.audiorx.CONNECT"
        const val ACTION_PLAY = "com.example.audiorx.PLAY"
        const val ACTION_STOP = "com.example.audiorx.STOP"
        const val ACTION_DISCONNECT = "com.example.audiorx.DISCONNECT"
        const val ACTION_TOGGLE_SPEAKER = "com.example.audiorx.TOGGLE_SPEAKER"
        const val EXTRA_MODE = "mode"
        const val EXTRA_CODE = "code"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_SIGNALING_URL = "signaling_url"
        const val EXTRA_SPEAKER_ON = "speaker_on"
        private const val CHANNEL_ID = "receiver_channel"
        private const val NOTIF_ID = 2001
    }
}
