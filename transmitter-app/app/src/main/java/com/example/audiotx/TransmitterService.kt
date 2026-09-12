package com.example.audiotx

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import org.java_websocket.WebSocket
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.net.URI
import java.text.Format

/**
 * The ONLY place in this app that touches the microphone in the background.
 * It cannot be started without the user tapping START in the UI (which
 * itself requires RECORD_AUDIO to already be granted), and it always shows
 * a foreground-service notification the whole time it runs - this is
 * enforced by the OS for foregroundServiceType="microphone", not just app
 * convention.
 *
 * Supports two signaling transports selected at start time:
 *  - LAN mode: spins up LocalSignalingServer + NsdAdvertiser, no internet
 *    server involved at all.
 *  - Internet mode: connects out to the Node.js relay as a WebSocket client.
 */
class TransmitterService : LifecycleService() {

    private var webRtcClient: WebRtcClient? = null
    private var localServer: LocalSignalingServer? = null
    private var nsdAdvertiser: NsdAdvertiser? = null
    private var remoteSignalingClient: SignalingClient? = null
    private var connectedLocalPeer: WebSocket? = null

    private var mode: String = MODE_LAN
    private var pairingCode: String = ""

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_LAN -> startLanMode()
            ACTION_START_INTERNET -> {
                val url = intent.getStringExtra(EXTRA_SIGNALING_URL) ?: return START_NOT_STICKY
                startInternetMode(url)
            }
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------
    // LAN (no-server) mode
    // ---------------------------------------------------------------------
    private fun startLanMode() {
        mode = MODE_LAN
        pairingCode = PairingManager.generateCode()
        val port = LocalSignalingServer.randomPort()

        localServer = LocalSignalingServer(
            port = port,
            onMessage = { conn, msg -> handleIncomingSignaling(msg, conn) },
            onPeerConnected = { conn ->
                connectedLocalPeer = conn
                beginWebRtcOffer()
            },
            onPeerDisconnected = { updateNotification("Receiver disconnected") }
        ).apply { start() }

        nsdAdvertiser = NsdAdvertiser(this).apply {
            advertise("AudioTx-$pairingCode", port)
        }

        val ip = localIpAddress()
        val payload = PairingManager.buildPayload(mode = "lan", code = pairingCode, host = ip, port = port)
        showForegroundNotification("Waiting for receiver to pair… code $pairingCode")
        PairingState.currentPayload = payload
        PairingState.status = "Waiting on LAN for a receiver (code $pairingCode)"
    }

    private fun localIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xff, ipInt shr 8 and 0xff, ipInt shr 16 and 0xff, ipInt shr 24 and 0xff
        )
    }

    // ---------------------------------------------------------------------
    // Internet mode
    // ---------------------------------------------------------------------
    private fun startInternetMode(signalingUrl: String) {
        mode = MODE_INTERNET
        showForegroundNotification("Connecting to signaling server…")

        remoteSignalingClient = SignalingClient(
            uri = URI(signalingUrl),
            onOpenCb = {
                remoteSignalingClient?.send(SignalingMessage(type = "create_room"))
            },
            onMessageCb = { msg -> handleIncomingSignaling(msg, null) },
            onCloseCb = { reason -> updateNotification("Signaling disconnected: $reason") }
        ).also { it.connect() }
    }

    // ---------------------------------------------------------------------
    // Common signaling handling
    // ---------------------------------------------------------------------
    private fun handleIncomingSignaling(msg: SignalingMessage, localConn: WebSocket?) {
        when (msg.type) {
            "room_created" -> {
                pairingCode = msg.code ?: return
                val payload = PairingManager.buildPayload(
                    mode = "internet",
                    code = pairingCode,
                    signalingUrl = "wss://YOUR_SERVER/ws" // set to the actual URL used
                )
                PairingState.currentPayload = payload
                PairingState.status = "Waiting on internet for a receiver (code $pairingCode)"
                updateNotification("Waiting for receiver… code $pairingCode")
            }
            "joined", "peer_joined" -> beginWebRtcOffer()
            "answer" -> {
                val sdp = msg.payload?.sdp ?: return
                webRtcClient?.onRemoteAnswer(SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "candidate" -> {
                val p = msg.payload ?: return
                webRtcClient?.onRemoteIceCandidate(
                    IceCandidate(p.sdpMid, p.sdpMLineIndex ?: 0, p.candidate)
                )
            }
            "bye", "peer_left" -> {
                updateNotification("Receiver disconnected")
                teardownWebRtc()
            }
            "error" -> updateNotification("Signaling error: ${msg.message}")
        }
    }

    private fun beginWebRtcOffer() {
        if (webRtcClient != null) return // already streaming
        webRtcClient = WebRtcClient(
            context = applicationContext,
            onLocalSdp = { sdp -> sendSignaling(SignalingMessage(
                type = "offer",
                code = pairingCode,
                payload = SdpOrCandidate(sdp = sdp.description, sdpType = "offer")
            )) },
            onLocalIceCandidate = { candidate -> sendSignaling(SignalingMessage(
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
                        PeerConnection.PeerConnectionState.CONNECTED ->
                            updateNotification("Streaming audio to paired receiver")
                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.FAILED ->
                            updateNotification("Connection lost")
                        else -> {}
                    }
                }
            }
        )
        val iceServers = if (mode == MODE_INTERNET) {
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
                // Add your self-hosted coturn TURN server here for strict-NAT clients, e.g.:
                // PeerConnection.IceServer.builder("turn:your.turn.server:3478")
                //     .setUsername("introuser").setPassword("...").createIceServer()
            )
        } else {
            emptyList() // LAN mode: host candidates only, no STUN/TURN needed
        }
        webRtcClient?.start(iceServers)
    }

    private fun sendSignaling(msg: SignalingMessage) {
        when (mode) {
            MODE_LAN -> connectedLocalPeer?.let { localServer?.sendTo(it, msg) }
            MODE_INTERNET -> remoteSignalingClient?.send(msg)
        }
    }

    // ---------------------------------------------------------------------
    // Stop / teardown
    // ---------------------------------------------------------------------
    private fun stopStreaming() {
        sendSignaling(SignalingMessage(type = "bye", code = pairingCode))
        teardownWebRtc()
        localServer?.stop()
        nsdAdvertiser?.stop()
        remoteSignalingClient?.close()
        PairingState.status = "Stopped"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardownWebRtc() {
        webRtcClient?.stop()
        webRtcClient = null
    }

    override fun onDestroy() {
        stopStreaming()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Notification (always visible while this service is alive)
    // ---------------------------------------------------------------------
    private fun showForegroundNotification(text: String) {
        val channel = NotificationChannel(
            CHANNEL_ID, "Audio streaming", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        val stopIntent = Intent(this, TransmitterService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Transmitter")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(0, "STOP", stopPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        PairingState.status = text
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Transmitter")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        const val ACTION_START_LAN = "com.example.audiotx.START_LAN"
        const val ACTION_START_INTERNET = "com.example.audiotx.START_INTERNET"
        const val ACTION_STOP = "com.example.audiotx.STOP"
        const val EXTRA_SIGNALING_URL = "signaling_url"
        const val MODE_LAN = "lan"
        const val MODE_INTERNET = "internet"
        private const val CHANNEL_ID = "transmitter_channel"
        private const val NOTIF_ID = 1001
    }
}

/** Tiny in-memory bridge so Compose UI can observe status/pairing info from the service. */
object PairingState {
    var currentPayload: PairingPayload? = null
    var status: String = "Idle"
}
