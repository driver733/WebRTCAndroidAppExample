package com.android.webrtc.example.session

import org.webrtc.*

/**
 * [PeerConnection.Observer] implementation with default callbacks and ability to override them
 * NOTE: This class is not mandatory but simplifies work with WebRTC.
 */
class PeerConnectionObserver(
    private val onIceCandidateCallback: (IceCandidate) -> Unit = {},
    private val onTrackCallback: (RtpTransceiver?) -> Unit = {},
    private val onRenegotiationNeededCallback: () -> Unit = {},
    private val onTrackRemoved: (RtpReceiver?) -> Unit = {},
) : PeerConnection.Observer {
    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
    }

    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
    }

    override fun onIceConnectionReceivingChange(p0: Boolean) {
    }

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
    }

    // called when LocalIceCandidate received
    override fun onIceCandidate(iceCandidate: IceCandidate?) {
        iceCandidate ?: return
        onIceCandidateCallback(iceCandidate)
    }

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
    }

    override fun onAddStream(mediaStream: MediaStream?) {
    }

    override fun onRemoveStream(p0: MediaStream?) {
    }

    override fun onDataChannel(p0: DataChannel?) {
    }

    override fun onRemoveTrack(receiver: RtpReceiver?) {
        onTrackRemoved(receiver)
        super.onRemoveTrack(receiver)
    }

    override fun onRenegotiationNeeded() {
        onRenegotiationNeededCallback()
    }

    // called when the remote track received
    override fun onTrack(transceiver: RtpTransceiver?) {
        super.onTrack(transceiver)
        onTrackCallback(transceiver)
    }
}