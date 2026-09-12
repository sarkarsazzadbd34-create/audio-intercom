package com.example.audiotx

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import kotlin.random.Random

/**
 * Runs an in-process WebSocket server so the Receiver can connect directly
 * over the LAN with no external signaling server. Also advertises itself
 * via Network Service Discovery (mDNS/NSD) so the Receiver can find it
 * without the user typing an IP address.
 *
 * Security note: this only ever binds to the phone's local network
 * interface and is torn down as soon as the pairing session ends. If you
 * want TLS here too, wrap `this` with an SSLContext via
 * WebSocketServer.setWebSocketFactory(DefaultSSLWebSocketServerFactory(...))
 * using a self-signed cert; the Receiver would then need to explicitly
 * trust that cert (shown to the user as a fingerprint at pairing time).
 */
class LocalSignalingServer(
    port: Int,
    private val onMessage: (WebSocket, SignalingMessage) -> Unit,
    private val onPeerConnected: (WebSocket) -> Unit,
    private val onPeerDisconnected: () -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    private val json = Json { ignoreUnknownKeys = true }
    var connectedPeer: WebSocket? = null
        private set

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        // Only allow a single receiver at a time.
        if (connectedPeer != null) {
            conn.close(1000, "already_paired")
            return
        }
        connectedPeer = conn
        onPeerConnected(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            onMessage(conn, json.decodeFromString(SignalingMessage.serializer(), message))
        } catch (e: Exception) {
            Log.e(TAG, "Bad local signaling message", e)
        }
    }

    fun sendTo(conn: WebSocket, msg: SignalingMessage) {
        conn.send(json.encodeToString(msg))
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        if (connectedPeer == conn) {
            connectedPeer = null
            onPeerDisconnected()
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "Local signaling server error", ex)
    }

    override fun onStart() {
        Log.i(TAG, "Local signaling server listening on port $port")
    }

    companion object {
        private const val TAG = "LocalSignalingServer"

        /** Picks a random high port in the ephemeral range. */
        fun randomPort(): Int = Random.nextInt(20000, 60000)
    }
}

/** Wraps Android's NsdManager to advertise the local server on the LAN. */
class NsdAdvertiser(private val context: Context) {
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun advertise(serviceName: String, port: Int) {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            this.port = port
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun stop() {
        registrationListener?.let { nsdManager?.unregisterService(it) }
        registrationListener = null
    }

    companion object {
        private const val TAG = "NsdAdvertiser"
        const val SERVICE_TYPE = "_audiotx._tcp."
    }
}
