package com.example.audiotx

import android.content.Context
import android.util.Log
import org.webrtc.*

/**
 * Owns the WebRTC PeerConnection on the transmitter side. Captures the mic
 * via WebRTC's built-in audio device module (which internally uses
 * AudioRecord) and streams it as a single audio-only track. Opus is
 * negotiated automatically by libwebrtc for the audio m-line - encryption
 * (DTLS-SRTP) is mandatory and cannot be disabled.
 *
 * All signaling (offer/ICE) is delivered via the callbacks the caller
 * supplies; this class never talks to the network directly, only to
 * libwebrtc.
 */
class WebRtcClient(
    context: Context,
    private val onLocalSdp: (SessionDescription) -> Unit,
    private val onLocalIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionStateChange: (PeerConnection.PeerConnectionState) -> Unit
) {
    private val eglBase: EglBase = EglBase.create()

    private val factory: PeerConnectionFactory = run {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(
                JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule()
            )
            .createPeerConnectionFactory()
    }

    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null

    /** ICE servers: STUN only needed for internet mode; harmless in LAN mode. */
    fun start(iceServers: List<PeerConnection.IceServer>) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) = onLocalIceCandidate(candidate)
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) =
                onConnectionStateChange(newState)

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("autoGainControl", "true"))
        }
        val audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("mic_audio", audioSource).apply {
            setEnabled(true)
        }
        peerConnection?.addTrack(localAudioTrack, listOf("mic_stream"))

        createOffer()
    }

    private fun createOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                onLocalSdp(sdp)
            }
        }, constraints)
    }

    fun onRemoteAnswer(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), sdp)
    }

    fun onRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    /** Mute/unmute without tearing down the connection. */
    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun stop() {
        localAudioTrack?.dispose()
        peerConnection?.close()
        peerConnection = null
        eglBase.release()
    }
}

/** Small adapter so we don't have to implement every SdpObserver method inline each time. */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) { Log.e("SdpObserverAdapter", "createFailure: $p0") }
    override fun onSetFailure(p0: String?) { Log.e("SdpObserverAdapter", "setFailure: $p0") }
}
