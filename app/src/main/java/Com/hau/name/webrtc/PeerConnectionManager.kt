package Com.hau.name.webrtc

import android.content.Context
import android.util.Log
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
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

private const val TAG = "PeerConnectionManager"

// Bitrate cho track video màn hình (Máy B -> Máy A). 1280px cạnh dài @20fps không cần quá cao;
// đặt trần vừa phải để tránh nghẽn khi đi qua TURN relay trên mạng di động, đồng thời đặt sàn
// đủ để chữ trên màn hình còn đọc được khi mạng tốt.
private const val MAX_VIDEO_BITRATE_BPS = 2_000_000
private const val MIN_VIDEO_BITRATE_BPS = 300_000

/** ICE servers dùng STUN công khai của Google + TURN dự phòng nếu 2 máy khác mạng LAN. */
private val ICE_SERVERS = listOf(
    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
    // TURN dự phòng — bắt buộc khi 2 máy ở sau CGNAT (mạng di động 4G/5G thường gặp),
    // vì lúc đó STUN không đủ để tìm đường kết nối trực tiếp, cần relay qua TURN.
    // Đây là TURN công khai miễn phí (Open Relay Project - metered.ca) dùng để demo/test;
    // triển khai thật lâu dài nên tự dựng coturn hoặc dùng dịch vụ TURN trả phí ổn định hơn.
    PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
        .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
        .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
        .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
)

/**
 * Bọc toàn bộ WebRTC PeerConnection lifecycle.
 *
 * [isHost] = true  → Máy B: tạo video track từ ScreenCapture rồi gửi offer
 * [isHost] = false → Máy A: nhận video track rồi render lên [remoteSink]
 */
class PeerConnectionManager(
    val factory: PeerConnectionFactory,
    private val isHost: Boolean,
    private val signalingClient: SignalingClient,
    /** Null trên Máy B (không cần render video của mình), non-null trên Máy A. */
    private val remoteSink: VideoSink? = null,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null

    /** Track video nhận được từ phía bên kia (chỉ có ý nghĩa khi [isHost] = false). */
    fun remoteVideoTrackOrNull(): VideoTrack? = remoteVideoTrack

    companion object {
        /**
         * Tạo 1 PeerConnectionFactory dùng chung cho nhiều PeerConnectionManager (vd. khi Máy A
         * kết nối nhiều Máy Camera cùng lúc) — tránh khởi tạo lại native encoder/decoder nhiều lần.
         * Gọi 1 lần, tự dispose ở nơi gọi khi không cần nữa.
         */
        fun createFactory(context: Context, eglBase: EglBase): PeerConnectionFactory {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val videoEncoderFactory: VideoEncoderFactory =
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val videoDecoderFactory: VideoDecoderFactory =
                DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            return PeerConnectionFactory.builder()
                .setVideoEncoderFactory(videoEncoderFactory)
                .setVideoDecoderFactory(videoDecoderFactory)
                .createPeerConnectionFactory()
        }
    }

    /** Khởi tạo PeerConnection và bắt đầu lắng nghe signaling. */
    fun init() {
        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient.sendIceCandidate(
                    candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp
                )
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "PeerConnection state: $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        signalingClient.markConnected()
                        onConnected()
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED -> onDisconnected()
                    else -> {}
                }
            }

            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                // Máy A nhận video track từ Máy B
                val track = transceiver?.receiver?.track() ?: return
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    if (remoteSink != null) track.addSink(remoteSink)
                }
            }

            // Stub callbacks bắt buộc
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        }) ?: throw IllegalStateException("Không tạo được PeerConnection — kiểm tra ICE server")

        // Cài sẵn transceiver để nhận video từ Máy B (cần trước khi tạo offer/answer)
        if (!isHost) {
            peerConnection?.addTransceiver(
                org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                org.webrtc.RtpTransceiver.RtpTransceiverInit(
                    org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                )
            )
        }

        signalingClient.start()
    }

    /**
     * Thêm video track từ ScreenCapture vào PeerConnection rồi gửi offer.
     * Chỉ gọi trên Máy B sau khi đã có [videoSource].
     */
    fun addVideoTrackAndOffer(videoSource: VideoSource) {
        localVideoTrack = factory.createVideoTrack("screen_track", videoSource)
        val sender = peerConnection?.addTrack(localVideoTrack!!, listOf("screen_stream"))
        sender?.let { configureVideoEncoding(it) }
        createAndSendOffer()
    }

    /**
     * Giới hạn bitrate tối đa cho track video màn hình. Không set thì WebRTC có thể ước lượng
     * bitrate ban đầu quá cao so với thực tế mạng di động/TURN relay, gây nghẽn hàng đợi gửi
     * và làm hình ảnh về Máy A bị lag/khựng thay vì hạ chất lượng mượt mà theo băng thông.
     * MAINTAIN_FRAMERATE: khi băng thông không đủ, ưu tiên giữ tốc độ khung hình (đỡ giật/lag,
     * đúng nhu cầu điều khiển từ xa) và chấp nhận giảm độ phân giải trước.
     */
    private fun configureVideoEncoding(sender: org.webrtc.RtpSender) {
        val params = sender.parameters
        if (params.encodings.isNotEmpty()) {
            val encoding = params.encodings[0]
            encoding.maxBitrateBps = MAX_VIDEO_BITRATE_BPS
            encoding.minBitrateBps = MIN_VIDEO_BITRATE_BPS
        }
        params.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        sender.parameters = params
    }

    /** Nhận offer từ Máy B, set remote description rồi tạo answer. */
    fun handleOffer(sdp: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(simpleSdpObserver("setRemoteDesc(offer)"), sessionDescription)
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(answer: SessionDescription) {
                peerConnection?.setLocalDescription(simpleSdpObserver("setLocalDesc(answer)"), answer)
                signalingClient.sendAnswer(answer.description)
            }
            override fun onCreateFailure(err: String?) { Log.e(TAG, "createAnswer fail: $err") }
            override fun onSetSuccess() {}
            override fun onSetFailure(err: String?) {}
        }, MediaConstraints())
    }

    /** Nhận answer từ Máy A, set remote description. */
    fun handleAnswer(sdp: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(simpleSdpObserver("setRemoteDesc(answer)"), sessionDescription)
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    private fun createAndSendOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(offer: SessionDescription) {
                peerConnection?.setLocalDescription(simpleSdpObserver("setLocalDesc(offer)"), offer)
                signalingClient.sendOffer(offer.description)
            }
            override fun onCreateFailure(err: String?) { Log.e(TAG, "createOffer fail: $err") }
            override fun onSetSuccess() {}
            override fun onSetFailure(err: String?) {}
        }, constraints)
    }

    private fun simpleSdpObserver(tag: String) = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() { Log.d(TAG, "$tag onSetSuccess") }
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(err: String?) { Log.e(TAG, "$tag onSetFailure: $err") }
    }

    fun release() {
        localVideoTrack?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        // KHÔNG dispose `factory` ở đây nữa — factory dùng chung, nơi tạo ra nó (Service)
        // chịu trách nhiệm dispose khi không còn phiên nào dùng tới.
    }
}
