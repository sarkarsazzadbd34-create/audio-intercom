package com.example.audiotx

import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

/**
 * Thin WebSocket client wrapper. Works identically whether the far end is
 * our own LocalSignalingServer (same-Wi-Fi mode) or the Node.js relay
 * (internet mode) - only the URI differs.
 */
class SignalingClient(
    uri: URI,
    private val onOpenCb: () -> Unit,
    private val onMessageCb: (SignalingMessage) -> Unit,
    private val onCloseCb: (String) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = object : WebSocketClient(uri) {
        override fun onOpen(handshakedata: ServerHandshake?) = onOpenCb()

        override fun onMessage(message: String?) {
            message ?: return
            try {
                onMessageCb(json.decodeFromString(SignalingMessage.serializer(), message))
            } catch (e: Exception) {
                Log.e(TAG, "Bad signaling message: $message", e)
            }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            onCloseCb(reason ?: "closed")
        }

        override fun onError(ex: Exception?) {
            Log.e(TAG, "Signaling socket error", ex)
        }
    }

    fun connect() = client.connect()

    fun send(msg: SignalingMessage) {
        client.send(json.encodeToString(msg))
    }

    fun close() {
        if (client.isOpen) client.close()
    }

    companion object {
        private const val TAG = "SignalingClient"
    }
}
