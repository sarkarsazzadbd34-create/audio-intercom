package com.example.audiorx

import android.content.Context
import android.media.AudioManager
import android.util.Log
import org.webrtc.*

/**
 * Owns the WebRTC PeerConnection on the receiver side. Accepts the
 * transmitter's offer, creates an answer, and lets WebRTC's built-in audio
 * device module (JavaAudioDeviceModule, backed by AudioTrack) play the
 * decoded Opus audio out through the speaker or whatever route Android's
 * AudioManager currently has selected (speaker / earpiece / Bluetooth).
 */
class WebRtcClient(
    private val context: Context,
    private val onLocalSdp: (SessionDescription) -> Unit,
    private val onLocalIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionStateChange: (PeerConnection.PeerConnectionState) -> Unit
) {
    private val eglBase: EglBase = EglBase.create()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val factory: PeerConnectionFactory = run {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(
                JavaAudioDeviceModule.builder(context)
                    .createAudioDeviceModule()
            )
            .createPeerConnectionFactory()
    }

    private var peerConnection: PeerConnection? = null
    private var remoteAudioTrack: AudioTrack? = null

    fun start(iceServers: List<PeerConnection.IceServer>) {
        // Route audio through the normal media/communication speaker path;
        // Android's AudioManager decides speaker vs. Bluetooth vs. wired
        // headset based on what's connected - we just ask for
        // MODE_IN_COMMUNICATION for low-latency full-duplex routing.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) = onLocalIceCandidate(candidate)
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) =
                onConnectionStateChange(newState)

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                // Nothing extra needed: WebRTC automatically starts playing
                // any received audio track through the AudioDeviceModule.
                // We only enable it explicitly for clarity/mute control:
                remoteAudioTrack = receiver.track() as? AudioTrack
                remoteAudioTrack?.setEnabled(true)
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })
    }

    /** Toggle between speaker and earpiece/Bluetooth; Bluetooth routing is otherwise automatic. */
    fun setSpeakerphoneOn(on: Boolean) {
        audioManager.isSpeakerphoneOn = on
    }

    /** STOP button: silences local playback without tearing down the WebRTC session. */
    fun setPlaybackEnabled(enabled: Boolean) {
        remoteAudioTrack?.setEnabled(enabled)
    }

    fun onRemoteOffer(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), sdp)
        createAnswer()
    }

    fun onRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    private fun createAnswer() {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                onLocalSdp(sdp)
            }
        }, constraints)
    }

    fun stop() {
        peerConnection?.close()
        peerConnection = null
        audioManager.mode = AudioManager.MODE_NORMAL
        eglBase.release()
    }
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) { Log.e("SdpObserverAdapter", "createFailure: $p0") }
    override fun onSetFailure(p0: String?) { Log.e("SdpObserverAdapter", "setFailure: $p0") }
}
