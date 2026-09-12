package com.example.audiotx

import kotlinx.serialization.Serializable

/**
 * Wire format shared by both apps and both transports (embedded local
 * WebSocket server for same-Wi-Fi mode, or the Node.js relay for internet
 * mode). Keeping the protocol identical means WebRtcClient doesn't need to
 * know which transport it's running over.
 */
@Serializable
data class SignalingMessage(
    val type: String,               // create_room | join_room | room_created | joined | offer | answer | candidate | bye | error | peer_left
    val code: String? = null,
    val message: String? = null,
    val payload: SdpOrCandidate? = null
)

@Serializable
data class SdpOrCandidate(
    val sdp: String? = null,
    val sdpType: String? = null,       // "offer" or "answer"
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null
)

/** What gets encoded into the QR code / typed manually on the receiver. */
@Serializable
data class PairingPayload(
    val mode: String,           // "lan" or "internet"
    val code: String,
    val host: String? = null,   // LAN mode: transmitter's local IP
    val port: Int? = null,      // LAN mode: local signaling server port
    val signalingUrl: String? = null // internet mode: wss://.../ws
)
