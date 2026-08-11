package Com.hau.name

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import Com.hau.name.webrtc.PeerConnectionManager
import Com.hau.name.webrtc.SignalingClient
import org.webrtc.Camera2Capturer
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

private const val TAG = "CameraStreamService"

/**
 * Foreground Service trên Máy B (camera giám sát):
 * 1. Bật camera SAU bằng Camera2Capturer (WebRTC), tạo VideoSource từ camera thật.
 * 2. Giữ 1 PARTIAL_WAKE_LOCK trong suốt vòng đời service -> CPU không ngủ (Doze) khi
 *    người dùng tắt màn hình, camera vẫn chạy và stream bình thường 24/7.
 * 3. Khởi tạo WebRTC PeerConnection (phía host/offer) và chờ Máy A nhập đúng mã kết nối tới.
 * 4. KHÔNG có kênh nhận lệnh điều khiển nào từ Máy A — đây là stream một chiều.
 * 5. Khi mất kết nối (Máy A ngắt, hoặc rớt mạng ở 1 trong 2 máy), service KHÔNG tự tắt —
 *    nó tự động thử kết nối lại trên CÙNG MÃ theo chu kỳ lùi dần (backoff), tối đa
 *    [MAX_RECONNECT_DELAY_MS]. Chỉ khi người dùng bấm "Kết thúc phiên" thì service mới dừng hẳn.
 */
class CameraStreamService : Service() {

    private var signalingClient: SignalingClient? = null
    private var peerConnectionManager: PeerConnectionManager? = null
    private var cameraCapturer: Camera2Capturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val eglBase: EglBase = EglBase.create()
    private var peerFactory: org.webrtc.PeerConnectionFactory? = null
    private var roomCode: String? = null
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private val reconnectRunnable = Runnable { roomCode?.let { attemptReconnect(it) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SHARING) {
            stopping = true
            handler.removeCallbacks(reconnectRunnable)
            cleanupSession()
            markRoomEnded()
            stopSelf()
            return START_NOT_STICKY
        }

        roomCode = intent?.getStringExtra(EXTRA_ROOM_CODE) ?: roomCode

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        acquireWakeLock()

        val code = roomCode
        if (code != null && videoSource == null) {
            startCameraAndWebRTC(code)
        } else if (code == null) {
            Log.e(TAG, "Thiếu roomCode — không thể bắt đầu camera")
            stopSelf()
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeCamera:CameraStreamService")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
    }

    /**
     * Nhiều điện thoại có nhiều ống kính SAU (thường/góc rộng/tele) — Camera2Enumerator không
     * đảm bảo trả về đúng ống góc rộng nhất trước. Hàm này tính góc nhìn (FOV) thực tế của từng
     * ống bằng thông số cảm biến + tiêu cự, chọn ống có FOV lớn nhất (quay được nhiều nhất trong
     * phòng) — quan trọng với camera giám sát cố định 1 góc, không xoay được.
     */
    private fun pickWidestBackCamera(enumerator: Camera2Enumerator): String? {
        val backCameras = enumerator.deviceNames.filter { enumerator.isBackFacing(it) }
        if (backCameras.isEmpty()) return enumerator.deviceNames.firstOrNull()
        if (backCameras.size == 1) return backCameras.first()

        val cameraManager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        return backCameras.maxByOrNull { id ->
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val sensorSize = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val focalLengths = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focal = focalLengths?.minOrNull() // tiêu cự nhỏ nhất = ống góc rộng nhất trong các mức zoom của ống đó
                if (sensorSize != null && focal != null && focal > 0f) {
                    // FOV ngang (độ) — chỉ cần so sánh tương đối nên không cần đổi ra radian
                    2.0 * Math.atan((sensorSize.width / (2.0 * focal)))
                } else 0.0
            } catch (e: Exception) {
                Log.w(TAG, "Không đọc được thông số ống kính $id: ${e.message}")
                0.0
            }
        }
    }

    private fun startCameraAndWebRTC(code: String) {
        surfaceTextureHelper = SurfaceTextureHelper.create("CameraCaptureThread", eglBase.eglBaseContext)

        val enumerator = Camera2Enumerator(this)
        val backCameraName = pickWidestBackCamera(enumerator)
        if (backCameraName == null) {
            Log.e(TAG, "Không tìm thấy camera nào trên thiết bị")
            stopSelf()
            return
        }
        cameraCapturer = Camera2Capturer(this, backCameraName, object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(errorDescription: String?) {
                Log.e(TAG, "Lỗi camera: $errorDescription")
            }
            override fun onCameraDisconnected() { Log.w(TAG, "Camera bị ngắt (app khác chiếm dụng?)") }
            override fun onCameraFreezed(errorDescription: String?) {}
            override fun onCameraOpening(cameraName: String?) {}
            override fun onFirstFrameAvailable() { Log.d(TAG, "Khung hình camera đầu tiên sẵn sàng") }
            override fun onCameraClosed() {}
        })

        openPeerConnectionAndOffer(code)
    }

    /** Tạo SignalingClient + PeerConnection mới, gắn videoSource (tạo mới nếu chưa có) rồi gửi offer. */
    private fun openPeerConnectionAndOffer(code: String) {
        val sigClient = SignalingClient(
            roomCode = code,
            isHost = true,
            listener = object : SignalingClient.Listener {
                override fun onOfferReceived(sdp: String) {}
                override fun onAnswerReceived(sdp: String) { peerConnectionManager?.handleAnswer(sdp) }
                override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    peerConnectionManager?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
                }
                override fun onRemoteDisconnected() {
                    Log.d(TAG, "Máy xem ngắt kết nối — sẽ tự kết nối lại")
                    scheduleReconnect(code)
                }
            }
        )
        signalingClient = sigClient

        val pcm = PeerConnectionManager(
            factory = peerFactory ?: PeerConnectionManager.createFactory(this, eglBase).also { peerFactory = it },
            isHost = true, signalingClient = sigClient,
            remoteSink = null,
            onConnected = {
                Log.d(TAG, "Đã kết nối với máy xem")
                reconnectAttempt = 0
                handler.removeCallbacks(reconnectRunnable)
            },
            onDisconnected = {
                Log.d(TAG, "Mất kết nối WebRTC (có thể do rớt mạng) — sẽ tự kết nối lại")
                scheduleReconnect(code)
            }
        )
        peerConnectionManager = pcm
        pcm.init()

        if (videoSource == null) {
            videoSource = pcm.factory.createVideoSource(false)
            cameraCapturer!!.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)
            // 1280x720 @ 20fps: đủ nét để nhận diện người/vật trong nhà, không quá nặng cho
            // encoder phần cứng của điện thoại cũ chạy liên tục 24/7.
            cameraCapturer!!.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
        }
        pcm.addVideoTrackAndOffer(videoSource!!)

        com.google.firebase.database.FirebaseDatabase.getInstance().reference
            .child("rooms").child(code).child("status").setValue("waiting")
    }

    /**
     * Lên lịch thử kết nối lại theo backoff tăng dần (2s, 4s, 8s... tối đa
     * [MAX_RECONNECT_DELAY_MS]) — tránh vòng lặp thử liên tục gây hao pin khi mất mạng kéo dài.
     * Camera + videoSource vẫn giữ nguyên, chỉ tạo lại PeerConnection.
     */
    private fun scheduleReconnect(code: String) {
        if (stopping) return
        peerConnectionManager?.release()
        peerConnectionManager = null
        signalingClient?.release()
        signalingClient = null

        handler.removeCallbacks(reconnectRunnable)
        val delay = minOf(
            BASE_RECONNECT_DELAY_MS * (1 shl reconnectAttempt.coerceAtMost(5)),
            MAX_RECONNECT_DELAY_MS
        )
        reconnectAttempt++
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun attemptReconnect(code: String) {
        if (stopping) return
        Log.d(TAG, "Thử kết nối lại (lần $reconnectAttempt) trên mã $code")
        openPeerConnectionAndOffer(code)
    }

    /** Chỉ báo Firebase là phòng đã đóng — mã cố định (KEY_FIXED_CODE) KHÔNG bị xoá, giữ lại
     *  để lần sau bấm "Bắt đầu làm Camera" dùng lại đúng mã cũ. */
    private fun markRoomEnded() {
        roomCode?.let { code ->
            com.google.firebase.database.FirebaseDatabase.getInstance().reference
                .child("rooms").child(code).child("status").setValue("ended")
        }
    }

    private fun buildNotification(): android.app.Notification {
        val channelId = "home_camera_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CameraStreamService::class.java).apply { action = ACTION_STOP_SHARING },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kết thúc", stopPendingIntent)
            .build()
    }

    private fun cleanupSession() {
        handler.removeCallbacks(reconnectRunnable)
        cameraCapturer?.stopCapture()
        cameraCapturer?.dispose()
        cameraCapturer = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        signalingClient?.release()
        signalingClient = null
        peerConnectionManager?.release()
        peerConnectionManager = null
        peerFactory?.dispose()
        peerFactory = null
        eglBase.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopping = true
        cleanupSession()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROOM_CODE = "extra_room_code"
        const val ACTION_STOP_SHARING = "action_stop_sharing"
        private const val NOTIF_ID = 43

        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720
        private const val CAPTURE_FPS = 20

        private const val BASE_RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
