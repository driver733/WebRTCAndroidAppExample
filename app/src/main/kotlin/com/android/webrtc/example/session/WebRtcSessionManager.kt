package com.android.webrtc.example.session

import android.content.Context
import com.android.webrtc.example.R
import com.android.webrtc.example.ioc.ServiceLocator.eglBaseContext
import com.android.webrtc.example.signaling.SignalingClient
import com.android.webrtc.example.signaling.SignalingCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import org.webrtc.*
import org.webrtc.CameraVideoCapturer.CameraEventsHandler
import org.webrtc.MediaStreamTrack.VIDEO_TRACK_KIND
import java.nio.file.Path
import java.util.*
import java.util.concurrent.Executors
import kotlin.io.path.createTempFile
import kotlin.io.path.outputStream
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime


private const val VIDEO_HEIGHT = 640
private const val VIDEO_WIDTH = 480
private const val VIDEO_FPS = 30

private const val ICE_SEPARATOR = '$'

class WebRtcSessionManager(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val peerConnectionUtils: PeerConnectionUtils
) {

    private var savedTrack: MediaStreamTrack? = null
    private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peerConnectionExecutor = Executors.newSingleThreadExecutor()
    private val cameraEnumerator = Camera2Enumerator(context)
    private var currentTrack: RtpSender? = null

    private var peerConnectionWasAlreadyCreated = false

    private var videoSourceType = VideoSourceType.BACK_CAMERA

    // used to send local video track to the fragment
    private val _localVideoSinkFlow = MutableSharedFlow<VideoTrack>()
    val localVideoSinkFlow: SharedFlow<VideoTrack> = _localVideoSinkFlow

    // used to send remote video track to the sender
    private val _remoteVideoSinkFlow = MutableSharedFlow<VideoTrack>()
    val remoteVideoSinkFlow: SharedFlow<VideoTrack> = _remoteVideoSinkFlow

    // declaring video constraints and setting OfferToReceiveVideo to true
    // this step is mandatory to create valid offer and answer
    private val mediaConstraints = MediaConstraints().apply {
        mandatory.add(
            MediaConstraints.KeyValuePair(
                "OfferToReceiveVideo", true.toString()
            )
        )
    }

    private val frontCameraVideoCapturer = getFrontCameraCapturer()
    private val backCameraVideoCapturer = getBackCameraCapturer()
    private val fileVideoCapturer by lazy { getFileCapturer() }

    // we need it to initialize video capturer
    private val surfaceTextureHelper = SurfaceTextureHelper.create(
        "SurfaceTextureHelperThread", eglBaseContext
    )

    private val peerConnectionFactory = peerConnectionUtils.peerConnectionFactory

    private val frontCameraVideoSource by lazy {
        getVideoSource(frontCameraVideoCapturer)
    }

    private val backCameraVideoSource by lazy {
        getVideoSource(backCameraVideoCapturer)
    }

    private val fileVideoSource by lazy {
        getVideoSource(fileVideoCapturer)
    }

    private var frontCameraVideoTrack: VideoTrack? = null
    private var backCameraVideoTrack: VideoTrack? = null
    private var fileVideoTrack: VideoTrack? = null

    private var onSessionScreenReadyWasCalled = false

    private fun createAndSetFrontCameraVideoTrack() {
        frontCameraVideoTrack = createVideoTrack("FrontCameraVideo", frontCameraVideoSource)
    }

    private fun createAndSetBackCameraVideoTrack() {
        backCameraVideoTrack = createVideoTrack("BackCameraVideo", backCameraVideoSource)
    }

    private fun createAndSetFileVideoTrack() {
        fileVideoTrack = createVideoTrack("VideoFile", fileVideoSource)
    }

    private fun getVideoSource(capturer: VideoCapturer) =
        peerConnectionFactory.createVideoSource(capturer.isScreencast).also {
            capturer.initialize(surfaceTextureHelper, context, it.capturerObserver)
            capturer.startCapture(VIDEO_HEIGHT, VIDEO_WIDTH, VIDEO_FPS)
        }

    private fun createVideoTrack(idPrefix: String, source: VideoSource) =
        peerConnectionFactory.createVideoTrack(
            "$idPrefix${UUID.randomUUID()}",
            source
        )

    private val peerConnection: PeerConnection by lazy {
        createNewPeerConnection() ?: error("peer connection initialization failed")
    }

    init {
        sessionManagerScope.launch {
            signalingClient.signalingCommandFlow
                .collect { (command, value) ->
                    when (command) {
                        SignalingCommand.OFFER -> onOfferReceived(value)
                        SignalingCommand.ANSWER -> onAnswerReceived(value)
                        SignalingCommand.ICE -> onIceReceived(value)
                        else -> {}
                    }
                }
        }
    }

    fun switchStreamFromBackCameraToFrontCamera() {
        peerConnection.removeTrack(currentTrack)
        backCameraVideoTrack = null
        createAndSetFrontCameraVideoTrack()
        currentTrack = peerConnection.addTrack(frontCameraVideoTrack)
        sessionManagerScope.launch {
            _localVideoSinkFlow.emit(frontCameraVideoTrack!!)
        }
        renegotiate()
    }

    fun switchStreamFromFrontCameraToBackCamera() {
        peerConnection.removeTrack(currentTrack)
        frontCameraVideoTrack = null
        createAndSetBackCameraVideoTrack()
        currentTrack = peerConnection.addTrack(backCameraVideoTrack)
        sessionManagerScope.launch {
            _localVideoSinkFlow.emit(backCameraVideoTrack!!)
        }
        renegotiate()
    }

    private fun startBackCameraStream() {
        createAndSetBackCameraVideoTrack()
        currentTrack = peerConnection.addTrack(backCameraVideoTrack)
        sessionManagerScope.launch {
            _localVideoSinkFlow.emit(backCameraVideoTrack!!)
        }
    }

    private fun startFrontCameraStream() {
        createAndSetFrontCameraVideoTrack()
        currentTrack = peerConnection.addTrack(frontCameraVideoTrack)
        sessionManagerScope.launch {
            _localVideoSinkFlow.emit(frontCameraVideoTrack!!)
        }
    }

    private fun startVideoFileStream() {
        createAndSetFileVideoTrack()
        currentTrack = peerConnection.addTrack(fileVideoTrack)
        sessionManagerScope.launch {
            _localVideoSinkFlow.emit(fileVideoTrack!!)
        }
    }

    // Not working yet
    private fun startCameraStream() {
        when (videoSourceType) {
            VideoSourceType.FRONT_CAMERA -> startFrontCameraStream()
            VideoSourceType.BACK_CAMERA -> startBackCameraStream()
        }
    }

    fun onSessionScreenReady() {
        peerConnectionExecutor.execute {
            onSessionScreenReadyWasCalled = true
            processSavedTrack()

//            startVideoFileStream()
//            startFrontCameraStream()
            startBackCameraStream()

            sendOffer()
        }
    }

    private fun processSavedTrack() {
        savedTrack?.also {
            emitTrackToRemoteVideoSinkFlow(it)
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun emitTrackToRemoteVideoSinkFlow(track: MediaStreamTrack) {
        sessionManagerScope.launch {
            delay(100.milliseconds)
            _remoteVideoSinkFlow.emit(track as VideoTrack)
        }
    }

    private fun sendOffer() {
        peerConnection.createOffer(
            createOfferObserver(),
            mediaConstraints
        )
    }

    private fun createOfferObserver() =
        CallbackSdpObserver(
            onCreate = {
                setLocalDescriptionToOfferAndSendIt(it)
            }
        )

    private fun sendAnswer(offer: String) {
        setRemoteDescriptionToOffer(offer)
        peerConnection.createAnswer(
            createAnswerObserver(),
            mediaConstraints
        )
    }

    private fun createAnswerObserver() =
        CallbackSdpObserver(
            onCreate = {
                setLocalDescriptionToAnswerAndSendIt(it)
            }
        )


    private fun setRemoteDescriptionToOffer(offer: String) {
        peerConnection.setRemoteDescription(
            CallbackSdpObserver(),
            SessionDescription(SessionDescription.Type.OFFER, offer)
        )
    }

    private fun setLocalDescriptionToAnswerAndSendIt(answer: SessionDescription) {
        peerConnection.setLocalDescription(
            sendMessageOnSuccessObserver(SignalingCommand.ANSWER, answer),
            answer
        )
    }

    private fun sendMessageOnSuccessObserver(type: SignalingCommand, message: SessionDescription) =
        CallbackSdpObserver(
            onSuccess = {
                signalingClient.sendCommand(type, message.description)
            }
        )

    private fun setLocalDescriptionToOfferAndSendIt(offer: SessionDescription) {
        printStats()
        peerConnection.setLocalDescription(
            sendMessageOnSuccessObserver(SignalingCommand.OFFER, offer),
            offer
        )
    }

    private fun printStats() {
        peerConnection.getStats {
            RTCStatsCollectorCallback {
                println(it.statsMap)
            }
        }
    }

    private fun onOfferReceived(offer: String) {
        sendAnswer(offer)
    }

    private fun onAnswerReceived(sdp: String) {
        peerConnection.setRemoteDescription(
            CallbackSdpObserver(),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    private fun onIceReceived(iceMessage: String) {
        peerConnectionExecutor.execute {
            peerConnection.addIceCandidate(iceCandidate(iceMessage))
        }
    }

    private fun iceCandidate(iceMessage: String): IceCandidate {
        val iceArray = iceMessage.split(ICE_SEPARATOR)
        return IceCandidate(
            iceArray[0], // sdpMid
            iceArray[1].toInt(), //sdpMLineIndex
            iceArray[2] // sdp
        )
    }

    private fun createNewPeerConnection(): PeerConnection? {
        return peerConnectionFactory.createPeerConnection(
            peerConnectionUtils.rtcConfig,
            PeerConnectionObserver(
                onIceCandidateCallback = {
                    signalingClient.sendCommand(
                        SignalingCommand.ICE,
                        "${it.sdpMid}$ICE_SEPARATOR${it.sdpMLineIndex}$ICE_SEPARATOR${it.sdp}"
                    )
                },
                onTrackCallback = {
                    it?.receiver?.track()?.also { track ->
                        if (track.kind() == VIDEO_TRACK_KIND) {
                            savedTrack = track
//                            if (onSessionScreenReadyWasCalled) {
                            emitTrackToRemoteVideoSinkFlow(track)
//                            }
                            println("onTrackCallback() - track added")
                        }
                    }
                },
                onRenegotiationNeededCallback = {
                    // This is not needed
//                    renegotiate()
                },
                onTrackRemoved = {
                    it?.track()?.also { track ->
                        if (track.kind() == VIDEO_TRACK_KIND) {
                            savedTrack = null
                            println("onTrackRemoved() - track removed")
                        }
                    }
                }
            )
        )
    }

    fun renegotiate() {
        sendOffer()
    }

    private fun getFrontCameraCapturer(): VideoCapturer =
        getCameraCapturer("Front") { cameraEnumerator.isFrontFacing(it) }

    private fun getBackCameraCapturer(): VideoCapturer =
        getCameraCapturer("Back") { cameraEnumerator.isBackFacing(it) }

    private fun getCameraCapturer(
        cameraErrorTitle: String,
        filter: (String) -> Boolean
    ): VideoCapturer =
        cameraEnumerator.deviceNames
            .firstOrNull(filter)
            ?.let { cameraEnumerator.createCapturer(it, object : CameraEventsHandler {
                override fun onCameraError(p0: String?) {
                    System.err.println("onCameraError: $p0")
                }

                override fun onCameraDisconnected() {
                    System.err.println("onCameraDisconnected")
                }

                override fun onCameraFreezed(p0: String?) {
                    System.err.println("onCameraFreezed: $p0")
                }

                override fun onCameraOpening(p0: String?) {
                    System.err.println("onCameraOpening: $p0")
                }

                override fun onFirstFrameAvailable() {
                    System.err.println("onFirstFrameAvailable")
                }

                override fun onCameraClosed() {
                    System.err.println("onCameraClosed")
                }

            }) }
            ?: error("$cameraErrorTitle camera does not exist")

    private fun getFileCapturer(): FileVideoCapturer {
        val videoFile = copyRawResourceToTempFile(R.raw.sample_video)
//        val videoFile = copyRawResourceToTempFile(R.raw.bus_orig)
        return FileVideoCapturer(videoFile.pathString)
    }

    private fun copyRawResourceToTempFile(id: Int): Path {
        val resourceInputStream = context.resources.openRawResource(id)
        val tempFile = createTempFile()
        resourceInputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }

    fun onSessionScreenDestroy() {
        peerConnectionExecutor.execute {
            currentTrack?.also { peerConnection.removeTrack(it) }
            backCameraVideoTrack = null
            frontCameraVideoTrack = null
            fileVideoTrack = null
            peerConnectionWasAlreadyCreated = true
            onSessionScreenReadyWasCalled = false
            switchVideoSource()
        }
    }

    private fun switchVideoSource() {
        videoSourceType =
            when (videoSourceType) {
                VideoSourceType.FRONT_CAMERA -> VideoSourceType.BACK_CAMERA
                VideoSourceType.BACK_CAMERA -> VideoSourceType.FRONT_CAMERA
            }
    }
}

private enum class VideoSourceType {
    FRONT_CAMERA,
    BACK_CAMERA,
//    FILE
}