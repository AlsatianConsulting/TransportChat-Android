package dev.alsatianconsulting.transportchat.transport.call

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WebRtcCallManager(
    context: Context,
    private val onSignalOut: (peerProfileId: String, payloadJson: String) -> Unit,
    private val onIncomingCall: (peerProfileId: String, audioOnly: Boolean) -> Unit,
    private val onMediaStateChanged: (
        peerProfileId: String,
        microphoneEnabled: Boolean,
        cameraEnabled: Boolean,
        localVideoAvailable: Boolean,
        remoteVideoAvailable: Boolean
    ) -> Unit,
    private val onCallState: (peerProfileId: String, state: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val eglBase: EglBase = EglBase.create()
    private val sessions = ConcurrentHashMap<String, CallSession>()

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBase.eglBaseContext,
                    true,
                    true
                )
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun eglBaseContext(): EglBase.Context = eglBase.eglBaseContext

    fun startOutgoingCall(peerProfileId: String, audioOnly: Boolean) {
        val session = sessions.computeIfAbsent(peerProfileId) {
            createSession(peerProfileId = peerProfileId, audioOnly = audioOnly)
        }
        session.audioOnly = audioOnly
        ensureLocalMedia(peerProfileId, session)

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", (!audioOnly).toString()))
        }

        session.peerConnection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                session.peerConnection.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                val payload = JSONObject()
                    .put("type", "offer")
                    .put("sdp", sessionDescription.description)
                    .put("audioOnly", audioOnly)
                    .toString()
                onSignalOut(peerProfileId, payload)
                onCallState(peerProfileId, "offer_sent")
            }

            override fun onCreateFailure(error: String?) {
                onCallState(peerProfileId, "offer_failed:${error.orEmpty()}")
            }
        }, constraints)
    }

    fun handleIncomingSignal(peerProfileId: String, payloadJson: String) {
        val message = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return
        val type = message.optString("type", "")

        when (type) {
            "offer" -> {
                val audioOnly = message.optBoolean("audioOnly", false)
                val session = sessions.computeIfAbsent(peerProfileId) {
                    createSession(peerProfileId = peerProfileId, audioOnly = audioOnly)
                }
                session.audioOnly = audioOnly
                session.pendingRemoteOffer = SessionDescription(
                    SessionDescription.Type.OFFER,
                    message.getString("sdp")
                )
                publishMediaState(peerProfileId, session)
                onIncomingCall(peerProfileId, audioOnly)
                onCallState(peerProfileId, "incoming")
            }

            "answer" -> {
                val session = sessions[peerProfileId] ?: return
                val answer = SessionDescription(SessionDescription.Type.ANSWER, message.getString("sdp"))
                session.peerConnection.setRemoteDescription(SimpleSdpObserver(), answer)
                onCallState(peerProfileId, "connected")
            }

            "ice" -> {
                val session = sessions[peerProfileId] ?: return
                val candidate = IceCandidate(
                    message.getString("sdpMid"),
                    message.getInt("sdpMLineIndex"),
                    message.getString("candidate")
                )
                session.peerConnection.addIceCandidate(candidate)
            }

            "hangup" -> {
                endCall(peerProfileId)
            }
        }
    }

    fun endCall(peerProfileId: String, notifyRemote: Boolean = false) {
        val session = sessions.remove(peerProfileId) ?: return
        if (notifyRemote) {
            onSignalOut(peerProfileId, JSONObject().put("type", "hangup").toString())
        }

        session.localVideoTrack?.let { track ->
            session.localVideoSinks.forEach(track::removeSink)
        }
        session.remoteVideoTrack?.let { track ->
            session.remoteVideoSinks.forEach(track::removeSink)
        }

        runCatching { session.videoCapturer?.stopCapture() }
        runCatching { session.videoCapturer?.dispose() }
        runCatching { session.videoSource?.dispose() }
        runCatching { session.audioSource?.dispose() }
        runCatching { session.localVideoTrack?.dispose() }
        runCatching { session.remoteVideoTrack?.dispose() }
        runCatching { session.localAudioTrack?.dispose() }
        runCatching { session.peerConnection.close() }
        runCatching { session.peerConnection.dispose() }
        runCatching { session.surfaceTextureHelper?.dispose() }

        onMediaStateChanged(
            peerProfileId,
            false,
            false,
            false,
            false
        )
        onCallState(peerProfileId, "ended")
    }

    fun acceptIncomingCall(peerProfileId: String) {
        val session = sessions[peerProfileId] ?: return
        val offer = session.pendingRemoteOffer ?: return
        session.pendingRemoteOffer = null
        ensureLocalMedia(peerProfileId, session)
        session.peerConnection.setRemoteDescription(SimpleSdpObserver(), offer)
        onCallState(peerProfileId, "answering")

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", (!session.audioOnly).toString()))
        }
        session.peerConnection.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                session.peerConnection.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                val payload = JSONObject()
                    .put("type", "answer")
                    .put("sdp", sessionDescription.description)
                    .toString()
                onSignalOut(peerProfileId, payload)
                onCallState(peerProfileId, "answer_sent")
            }

            override fun onCreateFailure(error: String?) {
                onCallState(peerProfileId, "answer_failed:${error.orEmpty()}")
            }
        }, constraints)
    }

    fun declineIncomingCall(peerProfileId: String) {
        if (!sessions.containsKey(peerProfileId)) return
        onSignalOut(peerProfileId, JSONObject().put("type", "hangup").put("reason", "declined").toString())
        endCall(peerProfileId, notifyRemote = false)
        onCallState(peerProfileId, "declined")
    }

    fun attachLocalVideoSink(peerProfileId: String, sink: VideoSink) {
        val session = sessions[peerProfileId] ?: return
        session.localVideoSinks += sink
        session.localVideoTrack?.addSink(sink)
    }

    fun detachLocalVideoSink(peerProfileId: String, sink: VideoSink) {
        val session = sessions[peerProfileId] ?: return
        session.localVideoSinks -= sink
        session.localVideoTrack?.removeSink(sink)
    }

    fun attachRemoteVideoSink(peerProfileId: String, sink: VideoSink) {
        val session = sessions[peerProfileId] ?: return
        session.remoteVideoSinks += sink
        session.remoteVideoTrack?.addSink(sink)
    }

    fun detachRemoteVideoSink(peerProfileId: String, sink: VideoSink) {
        val session = sessions[peerProfileId] ?: return
        session.remoteVideoSinks -= sink
        session.remoteVideoTrack?.removeSink(sink)
    }

    fun setMicrophoneEnabled(peerProfileId: String, enabled: Boolean): Boolean {
        val session = sessions[peerProfileId] ?: return false
        session.microphoneEnabled = enabled
        session.localAudioTrack?.setEnabled(enabled)
        publishMediaState(peerProfileId, session)
        return true
    }

    fun setCameraEnabled(peerProfileId: String, enabled: Boolean): Boolean {
        val session = sessions[peerProfileId] ?: return false
        val hasLocalVideo = session.localVideoTrack != null
        session.cameraEnabled = enabled && hasLocalVideo
        session.localVideoTrack?.setEnabled(session.cameraEnabled)
        publishMediaState(peerProfileId, session)
        return hasLocalVideo
    }

    fun releaseAll() {
        sessions.keys.toList().forEach { endCall(it) }
        runCatching { peerConnectionFactory.dispose() }
        runCatching { eglBase.release() }
    }

    private fun createSession(peerProfileId: String, audioOnly: Boolean): CallSession {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onCallState(peerProfileId, "ice_${state.name.lowercase()}")
            }

            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                onCallState(peerProfileId, "pc_${newState.name.lowercase()}")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

            override fun onIceCandidate(candidate: IceCandidate) {
                val payload = JSONObject()
                    .put("type", "ice")
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp)
                    .toString()
                onSignalOut(peerProfileId, payload)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(dataChannel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit

            override fun onTrack(transceiver: RtpTransceiver) {
                val session = sessions[peerProfileId] ?: return
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    val previousTrack = session.remoteVideoTrack
                    if (previousTrack !== track) {
                        previousTrack?.let { previous ->
                            session.remoteVideoSinks.forEach(previous::removeSink)
                        }
                        session.remoteVideoTrack = track
                        session.remoteVideoSinks.forEach(track::addSink)
                        publishMediaState(peerProfileId, session)
                    }
                }
            }
        }

        val peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
            ?: error("Unable to create PeerConnection")

        val session = CallSession(
            peerConnection = peerConnection,
            audioOnly = audioOnly,
            cameraEnabled = false
        )
        publishMediaState(peerProfileId, session)
        onCallState(peerProfileId, if (audioOnly) "voice_session_ready" else "video_session_ready")
        return session
    }

    private fun ensureLocalMedia(peerProfileId: String, session: CallSession) {
        if (session.localAudioTrack == null) {
            val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            val audioTrack = peerConnectionFactory.createAudioTrack("audio-${peerProfileId.take(8)}", audioSource)
            session.audioSource = audioSource
            session.localAudioTrack = audioTrack
            audioTrack.setEnabled(session.microphoneEnabled)
            session.peerConnection.addTrack(audioTrack, listOf(STREAM_ID))
        }

        if (!session.audioOnly && session.localVideoTrack == null) {
            val videoCapturer = createVideoCapturer(appContext)
            if (videoCapturer != null) {
                val surfaceTextureHelper = SurfaceTextureHelper.create(
                    "TransportChatCapture",
                    eglBase.eglBaseContext
                )
                val videoSource = peerConnectionFactory.createVideoSource(false)
                videoCapturer.initialize(surfaceTextureHelper, appContext, videoSource.capturerObserver)
                runCatching { videoCapturer.startCapture(640, 480, 24) }
                    .onFailure { Log.w(TAG, "Failed to start capture", it) }

                val videoTrack = peerConnectionFactory.createVideoTrack(
                    "video-${peerProfileId.take(8)}",
                    videoSource
                )
                session.videoCapturer = videoCapturer
                session.surfaceTextureHelper = surfaceTextureHelper
                session.videoSource = videoSource
                session.localVideoTrack = videoTrack
                session.cameraEnabled = true
                videoTrack.setEnabled(true)
                session.peerConnection.addTrack(videoTrack, listOf(STREAM_ID))
                session.localVideoSinks.forEach(videoTrack::addSink)
            } else {
                session.cameraEnabled = false
                onCallState(peerProfileId, "video_capture_unavailable")
            }
        }

        session.localVideoTrack?.setEnabled(session.cameraEnabled)
        publishMediaState(peerProfileId, session)
    }

    private fun publishMediaState(peerProfileId: String, session: CallSession) {
        onMediaStateChanged(
            peerProfileId,
            session.microphoneEnabled,
            session.cameraEnabled && session.localVideoTrack != null,
            session.localVideoTrack != null,
            session.remoteVideoTrack != null
        )
    }

    private fun createVideoCapturer(context: Context): CameraVideoCapturer? {
        val camera2Enumerator = Camera2Enumerator(context)
        val frontCamera = camera2Enumerator.deviceNames.firstOrNull { camera2Enumerator.isFrontFacing(it) }
        if (frontCamera != null) {
            return camera2Enumerator.createCapturer(frontCamera, null)
        }

        val anyCamera2 = camera2Enumerator.deviceNames.firstOrNull()
        if (anyCamera2 != null) {
            return camera2Enumerator.createCapturer(anyCamera2, null)
        }

        val camera1Enumerator = Camera1Enumerator(false)
        val frontCamera1 = camera1Enumerator.deviceNames.firstOrNull { camera1Enumerator.isFrontFacing(it) }
        if (frontCamera1 != null) {
            return camera1Enumerator.createCapturer(frontCamera1, null)
        }

        val anyCamera1 = camera1Enumerator.deviceNames.firstOrNull()
        return anyCamera1?.let { camera1Enumerator.createCapturer(it, null) }
    }

    private data class CallSession(
        val peerConnection: PeerConnection,
        var audioSource: AudioSource? = null,
        var localAudioTrack: AudioTrack? = null,
        var videoSource: VideoSource? = null,
        var localVideoTrack: VideoTrack? = null,
        var remoteVideoTrack: VideoTrack? = null,
        var surfaceTextureHelper: SurfaceTextureHelper? = null,
        var videoCapturer: VideoCapturer? = null,
        var audioOnly: Boolean,
        var pendingRemoteOffer: SessionDescription? = null,
        var microphoneEnabled: Boolean = true,
        var cameraEnabled: Boolean = false,
        val localVideoSinks: MutableSet<VideoSink> = CopyOnWriteArraySet(),
        val remoteVideoSinks: MutableSet<VideoSink> = CopyOnWriteArraySet()
    )

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    companion object {
        private const val TAG = "WebRtcCallManager"
        private const val STREAM_ID = "transportchat_stream"
    }
}
