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
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import org.webrtc.Camera2Capturer
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

private const val TAG = "CameraStreamService"

/** Số máy xem tối đa mà 1 camera phục vụ được cùng lúc. */
const val MAX_VIEWERS_PER_CAMERA = 4

/** Trạng thái kết nối riêng của từng máy xem đang được camera này phục vụ. */
private class ViewerConn(val viewerId: String) {
    var signalingClient: SignalingClient? = null
    var peerConnectionManager: PeerConnectionManager? = null
    var reconnectAttempt = 0
    var removed = false
    var reconnectRunnable: Runnable? = null
}

/**
 * Foreground Service trên Máy B (camera giám sát):
 * 1. Bật camera SAU bằng Camera2Capturer (WebRTC), tạo 1 VideoSource DÙNG CHUNG từ camera thật.
 * 2. Giữ 1 PARTIAL_WAKE_LOCK trong suốt vòng đời service -> CPU không ngủ (Doze) khi
 *    người dùng tắt màn hình, camera vẫn chạy và stream bình thường 24/7.
 * 3. Theo dõi danh sách máy xem (rooms/{code}/viewers) trên Firebase — mỗi máy xem mới xuất
 *    hiện sẽ được cấp 1 PeerConnection RIÊNG (dùng chung 1 VideoSource) để phục vụ độc lập,
 *    tối đa [MAX_VIEWERS_PER_CAMERA] máy cùng lúc.
 * 4. KHÔNG có kênh nhận lệnh điều khiển nào từ Máy A — đây là stream một chiều.
 * 5. Khi 1 máy xem mất kết nối, CHỈ kênh của máy đó tự kết nối lại (backoff) — không ảnh hưởng
 *    các máy xem khác đang xem bình thường. Khi máy xem chủ động rời hẳn, slot của nó được
 *    giải phóng cho máy xem khác.
 */
class CameraStreamService : Service() {

    private val viewerConns = LinkedHashMap<String, ViewerConn>()
    private var viewersListenerRef: DatabaseReference? = null
    private var viewersChildListener: ChildEventListener? = null

    private var cameraCapturer: Camera2Capturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val eglBase: EglBase = EglBase.create()
    private var peerFactory: org.webrtc.PeerConnectionFactory? = null
    private var roomCode: String? = null
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SHARING) {
            stopping = true
            cleanupSession()
            markRoomEnded()
            // Dọn cờ "đang chạy thật" dù dừng từ notification (không mở CameraActivity) — để
            // lần sau mở lại app, UI không hiện nhầm là đang hoạt động.
            getSharedPreferences(CameraActivity.PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(CameraActivity.KEY_SESSION_ACTIVE, false).apply()
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
            startCameraThenWatchViewers(code)
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
     * ống bằng thông số cảm biến + tiêu cự, chọn ống có FOV lớn nhất.
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
                val focal = focalLengths?.minOrNull()
                if (sensorSize != null && focal != null && focal > 0f) {
                    2.0 * Math.atan((sensorSize.width / (2.0 * focal)))
                } else 0.0
            } catch (e: Exception) {
                Log.w(TAG, "Không đọc được thông số ống kính $id: ${e.message}")
                0.0
            }
        }
    }

    /** Bật camera 1 lần duy nhất, tạo VideoSource dùng chung, rồi bắt đầu theo dõi máy xem. */
    private fun startCameraThenWatchViewers(code: String) {
        surfaceTextureHelper = SurfaceTextureHelper.create("CameraCaptureThread", eglBase.eglBaseContext)

        val enumerator = Camera2Enumerator(this)
        val backCameraName = pickWidestBackCamera(enumerator)
        if (backCameraName == null) {
            Log.e(TAG, "Không tìm thấy camera nào trên thiết bị")
            stopSelf()
            return
        }
        cameraCapturer = Camera2Capturer(this, backCameraName, object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(errorDescription: String?) { Log.e(TAG, "Lỗi camera: $errorDescription") }
            override fun onCameraDisconnected() { Log.w(TAG, "Camera bị ngắt (app khác chiếm dụng?)") }
            override fun onCameraFreezed(errorDescription: String?) {}
            override fun onCameraOpening(cameraName: String?) {}
            override fun onFirstFrameAvailable() { Log.d(TAG, "Khung hình camera đầu tiên sẵn sàng") }
            override fun onCameraClosed() {}
        })

        val factory = peerFactory ?: PeerConnectionManager.createFactory(this, eglBase).also { peerFactory = it }
        videoSource = factory.createVideoSource(false)
        cameraCapturer!!.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)
        // 1280x720 @ 20fps: đủ nét để nhận diện người/vật trong nhà, không quá nặng cho encoder
        // phần cứng của điện thoại cũ khi phải phục vụ cùng lúc nhiều máy xem.
        cameraCapturer!!.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)

        com.google.firebase.database.FirebaseDatabase.getInstance().reference
            .child("rooms").child(code).child("consentGivenAt").setValue(System.currentTimeMillis())

        watchViewers(code)
    }

    /**
     * Theo dõi danh sách máy xem trên Firebase (rooms/{code}/viewers). Mỗi máy xem MỚI xuất
     * hiện lần đầu (onChildAdded) được cấp 1 kênh kết nối riêng, tối đa [MAX_VIEWERS_PER_CAMERA].
     * Máy xem rời hẳn (onChildRemoved — do chủ động ngắt) được dọn dẹp, giải phóng slot.
     *
     * DỌN "VIEWER MA": nếu máy xem bị tắt đột ngột (mất pin, force-close, rớt mạng vĩnh viễn),
     * Firebase chỉ tự xoá field "present" (qua onDisconnect()) — KHÔNG xoá cả node viewerId,
     * để lại dữ liệu rác (gen/, hostGeneration). Khi camera khởi động lại, Firebase bắn
     * onChildAdded cho MỌI child hiện có, kể cả node rác này. Máy xem thật LUÔN ghi "present"
     * TRƯỚC khi camera kịp thấy node được tạo (xem SignalingClient.start()), nên node nào
     * KHÔNG có "present" chắc chắn là rác — xoá ngay, không cấp slot cho nó.
     */
    private fun watchViewers(code: String) {
        val ref = FirebaseDatabase.getInstance().reference.child("rooms").child(code).child("viewers")
        viewersListenerRef = ref
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prevKey: String?) {
                val viewerId = snapshot.key ?: return
                if (viewerConns.containsKey(viewerId)) return
                if (!snapshot.hasChild("present")) {
                    Log.d(TAG, "Dọn viewer rác (không có 'present'): $viewerId")
                    snapshot.ref.removeValue()
                    return
                }
                if (viewerConns.size >= MAX_VIEWERS_PER_CAMERA) {
                    Log.w(TAG, "Đã đủ $MAX_VIEWERS_PER_CAMERA máy xem, bỏ qua máy xem mới: $viewerId")
                    return
                }
                val conn = ViewerConn(viewerId)
                conn.reconnectRunnable = Runnable { if (!conn.removed) connectViewer(code, conn) }
                viewerConns[viewerId] = conn
                connectViewer(code, conn)
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val viewerId = snapshot.key ?: return
                val conn = viewerConns.remove(viewerId) ?: return
                conn.removed = true
                conn.reconnectRunnable?.let { handler.removeCallbacks(it) }
                conn.peerConnectionManager?.release()
                conn.signalingClient?.release()
                Log.d(TAG, "Máy xem $viewerId đã rời hẳn, giải phóng slot")
            }
            override fun onChildChanged(snapshot: DataSnapshot, prevKey: String?) {}
            override fun onChildMoved(snapshot: DataSnapshot, prevKey: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        viewersChildListener = listener
        ref.addChildEventListener(listener)
    }

    /** Mở kênh WebRTC riêng cho 1 máy xem, gửi offer, gắn cùng VideoSource dùng chung. */
    private fun connectViewer(code: String, conn: ViewerConn) {
        if (stopping || conn.removed) return
        val vSource = videoSource ?: return

        val sigClient = SignalingClient(
            roomCode = code, viewerId = conn.viewerId, isHost = true,
            listener = object : SignalingClient.Listener {
                override fun onOfferReceived(sdp: String) {}
                override fun onAnswerReceived(sdp: String) { conn.peerConnectionManager?.handleAnswer(sdp) }
                override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    conn.peerConnectionManager?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
                }
                override fun onRemoteDisconnected() {
                    Log.d(TAG, "Máy xem ${conn.viewerId} mất kết nối tạm thời — sẽ tự nối lại")
                    scheduleReconnectForViewer(code, conn)
                }
            }
        )
        conn.signalingClient = sigClient

        val pcm = PeerConnectionManager(
            factory = peerFactory!!, isHost = true, signalingClient = sigClient, remoteSink = null,
            onConnected = {
                Log.d(TAG, "Đã kết nối với máy xem ${conn.viewerId}")
                conn.reconnectAttempt = 0
                conn.reconnectRunnable?.let { handler.removeCallbacks(it) }
                updateNotification()
            },
            onDisconnected = {
                Log.d(TAG, "Mất kết nối WebRTC với máy xem ${conn.viewerId} — sẽ tự nối lại")
                scheduleReconnectForViewer(code, conn)
            }
        )
        conn.peerConnectionManager = pcm
        pcm.init()
        pcm.addVideoTrackAndOffer(vSource)
        updateNotification()
    }

    /** Backoff riêng cho từng máy xem — 1 máy rớt mạng không ảnh hưởng các máy khác. */
    private fun scheduleReconnectForViewer(code: String, conn: ViewerConn) {
        if (stopping || conn.removed) return
        conn.peerConnectionManager?.release()
        conn.peerConnectionManager = null
        conn.signalingClient?.release()
        conn.signalingClient = null

        conn.reconnectRunnable?.let { handler.removeCallbacks(it) }
        val delay = minOf(
            BASE_RECONNECT_DELAY_MS * (1 shl conn.reconnectAttempt.coerceAtMost(5)),
            MAX_RECONNECT_DELAY_MS
        )
        conn.reconnectAttempt++
        conn.reconnectRunnable?.let { handler.postDelayed(it, delay) }
    }

    /** Chỉ đánh dấu phòng đã đóng — mã cố định KHÔNG bị xoá, giữ lại dùng cho lần sau.
     *  Gọi từ CẢ 2 đường dừng camera (nút "Kết thúc" trên notification VÀ nút trong app) để
     *  luôn dọn sạch danh sách máy xem trên Firebase như nhau, không để rác lại tuỳ đường dừng. */
    private fun markRoomEnded() {
        roomCode?.let { code ->
            val roomRef = FirebaseDatabase.getInstance().reference.child("rooms").child(code)
            roomRef.child("status").setValue("ended")
            roomRef.child("viewers").removeValue()
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
        val connectedCount = viewerConns.values.count { it.peerConnectionManager != null && it.reconnectAttempt == 0 }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("$connectedCount/$MAX_VIEWERS_PER_CAMERA máy đang xem. Chạm để kết thúc.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kết thúc", stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification())
    }

    private fun cleanupSession() {
        viewerConns.values.forEach { conn ->
            conn.removed = true
            conn.reconnectRunnable?.let { handler.removeCallbacks(it) }
            conn.peerConnectionManager?.release()
            conn.signalingClient?.release()
        }
        viewerConns.clear()
        viewersChildListener?.let { viewersListenerRef?.removeEventListener(it) }
        viewersChildListener = null
        viewersListenerRef = null

        cameraCapturer?.stopCapture()
        cameraCapturer?.dispose()
        cameraCapturer = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
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
