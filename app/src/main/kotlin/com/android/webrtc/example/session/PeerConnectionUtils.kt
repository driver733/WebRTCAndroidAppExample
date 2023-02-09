package com.android.webrtc.example.session

import android.content.Context
import org.webrtc.BuiltinAudioDecoderFactoryFactory
import org.webrtc.BuiltinAudioEncoderFactoryFactory
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory

/**
 * Provides base WebRTC instances [PeerConnectionFactory] and [PeerConnection.RTCConfiguration]
 * NOTE: This class is not mandatory but simplifies work with WebRTC.
 */
class PeerConnectionUtils(
    context: Context,
    eglBaseContext: EglBase.Context
) {

    init {
        PeerConnectionFactory.InitializationOptions
            .builder(context)
            .createInitializationOptions().also { initializationOptions ->
                PeerConnectionFactory.initialize(initializationOptions)
            }
    }

    // Creating peer connection factory. We need it to create "PeerConnections"
    val peerConnectionFactory: PeerConnectionFactory = PeerConnectionFactory
    .builder()
    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext, true, true))
    .setOptions(PeerConnectionFactory.Options().apply {
        disableEncryption = true
        disableNetworkMonitor = true
    })
    .createPeerConnectionFactory()

    // rtcConfig contains STUN and TURN servers list
    val rtcConfig = PeerConnection.RTCConfiguration(
        arrayListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun5.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun6.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:relay.metered.ca:80").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80").setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        )
    ).apply {
        // it's very important to use new unified sdp semantics PLAN_B is deprecated
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }
}