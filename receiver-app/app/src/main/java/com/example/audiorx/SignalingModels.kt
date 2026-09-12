package com.example.audiorx

import kotlinx.serialization.Serializable

/** Must stay in sync with the transmitter app's SignalingModels.kt - same wire protocol. */
@Serializable
data class SignalingMessage(
    val type: String,
    val code: String? = null,
    val message: String? = null,
    val payload: SdpOrCandidate? = null
)

@Serializable
data class SdpOrCandidate(
    val sdp: String? = null,
    val sdpType: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null
)

/** Decoded from the QR code / manually-entered fields. */
@Serializable
data class PairingPayload(
    val mode: String,           // "lan" or "internet"
    val code: String,
    val host: String? = null,
    val port: Int? = null,
    val signalingUrl: String? = null
)
