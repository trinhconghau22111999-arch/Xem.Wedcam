package Com.hau.name

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import Com.hau.name.webrtc.PeerConnectionManager
import Com.hau.name.webrtc.SignalingClient
import org.webrtc.EglBase
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.File

private const val TAG = "ViewerRecordingService"

const val MAX_CAMERAS = 4
const val MAX_RECORDING_CAMERAS = 4

/** Trạng thái hiện tại của 1 camera đã thêm vào máy xem, hiển thị cho UI. */
enum class CameraConnState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED }

data class CameraSummary(
    val roomCode: String,
    val label: String,
    val recordingEnabled: Boolean,
    val state: CameraConnState
)

/**
 * Một phiên kết nối tới 1 Máy Camera (B), giữ toàn bộ state WebRTC + ghi hình + reconnect
 * riêng cho camera đó. Máy xem (A) có thể giữ tối đa [MAX_CAMERAS] session cùng lúc.
 */
private class CameraSession(
    val roomCode: String,
    var label: String,
    var recordingEnabled: Boolean
) {
    var signalingClient: SignalingClient? = null
    var peerConnectionManager: PeerConnectionManager? = null
    var remoteVideoTrack: VideoTrack? = null
    var segmentedRecorder: SegmentedRecorder? = null
    var previewSink: VideoSink? = null
    var state: CameraConnState = CameraConnState.CONNECTING
    var reconnectAttempt = 0
    var removed = false
    var reconnectRunnable: Runnable? = null
}

/**
 * Foreground Service trên Máy A (máy xem):
 * - Quản lý tối đa [MAX_CAMERAS] kết nối WebRTC song song, mỗi kết nối tới 1 Máy Camera
 *   khác nhau (mỗi máy có mã 6 số riêng).
 * - Tối đa [MAX_RECORDING_CAMERAS] trong số đó được BẬT GHI HÌNH (SegmentedRecorder, cắt
 *   đoạn 30 phút, lưu vào thư viện qua [MediaStoreVideoSaver]); các camera còn lại chỉ
 *   dùng để xem trực tiếp (live view), không ghi.
 * - Mỗi camera tự động thử kết nối lại theo backoff khi rớt mạng/mất kết nối, độc lập
 *   với các camera khác — 1 camera rớt không ảnh hưởng camera khác.
 * - Toàn bộ chạy nền, độc lập vòng đời Activity.
 */
class ViewerRecordingService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): ViewerRecordingService = this@ViewerRecordingService
    }
    private val binder = LocalBinder()

    private val sessions = LinkedHashMap<String, CameraSession>()
    private val eglBase: EglBase = EglBase.create()
    private var peerFactory: org.webrtc.PeerConnectionFactory? = null
    private val handler = Handler(Looper.getMainLooper())

    var listener: Listener? = null
    interface Listener {
        fun onCameraStateChanged(roomCode: String, state: CameraConnState, message: String?)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            sessions.keys.toList().forEach { removeCamera(it) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        // Không xử lý gì thêm ở đây — thêm/xoá camera thực hiện qua các hàm public bên dưới,
        // gọi trực tiếp từ ViewerActivity sau khi bind().
        return START_STICKY
    }

    fun getEglBaseContext(): EglBase.Context = eglBase.eglBaseContext

    fun listCameras(): List<CameraSummary> = sessions.values.map {
        CameraSummary(it.roomCode, it.label, it.recordingEnabled, it.state)
    }

    fun recordingCount(): Int = sessions.values.count { it.recordingEnabled }

    /**
     * Thêm 1 camera mới bằng mã 6 số. Trả về thông báo lỗi (String) nếu không thêm được
     * (đã đủ 6 máy, đã đủ 2 máy ghi hình, hoặc mã đã được thêm rồi), null nếu thành công.
     */
    fun addCamera(code: String, label: String, wantRecording: Boolean): String? {
        if (sessions.containsKey(code)) return "Camera với mã này đã được thêm rồi"
        if (sessions.size >= MAX_CAMERAS) return "Đã đạt tối đa $MAX_CAMERAS camera"
        if (wantRecording && recordingCount() >= MAX_RECORDING_CAMERAS) {
            return "Đã đạt tối đa $MAX_RECORDING_CAMERAS camera được phép ghi hình"
        }
        val session = CameraSession(code, label, wantRecording)
        session.reconnectRunnable = Runnable { if (!session.removed) connectSession(session) }
        sessions[code] = session
        connectSession(session)
        updateNotification()
        return null
    }

    /** Bật/tắt ghi hình cho 1 camera đang xem. Trả về thông báo lỗi nếu vượt giới hạn. */
    fun setRecordingEnabled(code: String, enabled: Boolean): String? {
        val session = sessions[code] ?: return "Không tìm thấy camera"
        if (session.recordingEnabled == enabled) return null
        if (enabled && recordingCount() >= MAX_RECORDING_CAMERAS) {
            return "Đã đạt tối đa $MAX_RECORDING_CAMERAS camera được phép ghi hình"
        }
        session.recordingEnabled = enabled
        if (enabled) {
            session.remoteVideoTrack?.let { attachRecorder(session, it) }
        } else {
            session.segmentedRecorder?.let { rec -> session.remoteVideoTrack?.removeSink(rec) }
            session.segmentedRecorder?.stop()
            session.segmentedRecorder = null
        }
        return null
    }

    fun removeCamera(code: String) {
        val session = sessions.remove(code) ?: return
        session.removed = true
        session.reconnectRunnable?.let { handler.removeCallbacks(it) }
        teardownSession(session, finalizeRecording = true)
        updateNotification()
        if (sessions.isEmpty()) stopSelf()
    }

    fun attachPreview(code: String, sink: VideoSink) {
        val session = sessions[code] ?: return
        session.previewSink = sink
        session.remoteVideoTrack?.addSink(sink)
    }

    fun detachPreview(code: String, sink: VideoSink) {
        val session = sessions[code] ?: return
        session.remoteVideoTrack?.removeSink(sink)
        if (session.previewSink === sink) session.previewSink = null
    }

    // ---- Kết nối / tự kết nối lại ----

    private fun connectSession(session: CameraSession) {
        if (session.removed) return
        session.state = CameraConnState.CONNECTING
        listener?.onCameraStateChanged(session.roomCode, session.state, null)

        val sigClient = SignalingClient(
            roomCode = session.roomCode,
            isHost = false,
            listener = object : SignalingClient.Listener {
                override fun onOfferReceived(sdp: String) { session.peerConnectionManager?.handleOffer(sdp) }
                override fun onAnswerReceived(sdp: String) {}
                override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    session.peerConnectionManager?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
                }
                override fun onRemoteDisconnected() { handleSessionDisconnected(session, "Camera đã ngắt kết nối") }
            }
        )
        session.signalingClient = sigClient

        val pcm = PeerConnectionManager(
            factory = peerFactory ?: PeerConnectionManager.createFactory(this, eglBase).also { peerFactory = it },
            isHost = false, signalingClient = sigClient,
            remoteSink = null,
            onConnected = {
                Log.d(TAG, "Đã kết nối camera ${session.roomCode}")
                session.reconnectAttempt = 0
                session.state = CameraConnState.CONNECTED
                val track = session.peerConnectionManager?.remoteVideoTrackOrNull()
                session.remoteVideoTrack = track
                track?.let { t ->
                    session.previewSink?.let { t.addSink(it) }
                    if (session.recordingEnabled) attachRecorder(session, t)
                }
                listener?.onCameraStateChanged(session.roomCode, session.state, null)
                updateNotification()
            },
            onDisconnected = { handleSessionDisconnected(session, "Mất kết nối tới camera") }
        )
        session.peerConnectionManager = pcm
        pcm.init()
    }

    private fun handleSessionDisconnected(session: CameraSession, reason: String) {
        if (session.removed) return
        session.peerConnectionManager?.release()
        session.peerConnectionManager = null
        session.signalingClient?.release()
        session.signalingClient = null
        session.remoteVideoTrack = null

        // Chốt đoạn video đang ghi dở lại đúng cách — không mất phần đã quay được.
        session.segmentedRecorder?.stop()
        session.segmentedRecorder = null

        session.state = CameraConnState.RECONNECTING
        listener?.onCameraStateChanged(session.roomCode, session.state, reason)
        updateNotification()

        val delay = minOf(
            BASE_RECONNECT_DELAY_MS * (1 shl session.reconnectAttempt.coerceAtMost(5)),
            MAX_RECONNECT_DELAY_MS
        )
        session.reconnectAttempt++
        session.reconnectRunnable?.let { handler.postDelayed(it, delay) }
    }

    private fun attachRecorder(session: CameraSession, track: VideoTrack) {
        if (session.segmentedRecorder != null) return
        val outDir = File(getExternalFilesDir(null), "HomeCamera_tmp_${session.roomCode}")
        val recorder = SegmentedRecorder(
            eglContext = eglBase.eglBaseContext,
            outputDir = outDir,
            onSegmentSaved = { file ->
                Log.d(TAG, "Đoạn video camera ${session.roomCode} đã ghi xong: ${file.name}")
                MediaStoreVideoSaver.saveToGallery(applicationContext, file, session.label)
            }
        )
        session.segmentedRecorder = recorder
        track.addSink(recorder)
    }

    private fun teardownSession(session: CameraSession, finalizeRecording: Boolean) {
        session.remoteVideoTrack?.let { track ->
            session.previewSink?.let { track.removeSink(it) }
            session.segmentedRecorder?.let { track.removeSink(it) }
        }
        if (finalizeRecording) session.segmentedRecorder?.stop()
        session.segmentedRecorder = null
        session.peerConnectionManager?.release()
        session.peerConnectionManager = null
        session.signalingClient?.release()
        session.signalingClient = null
    }

    private fun buildNotification(): android.app.Notification {
        val channelId = "viewer_recording_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.viewer_notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ViewerRecordingService::class.java).apply { action = ACTION_STOP_ALL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val connected = sessions.values.count { it.state == CameraConnState.CONNECTED }
        val recording = recordingCount()
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.viewer_notif_title))
            .setContentText("$connected/${sessions.size} camera đang xem · $recording đang ghi hình")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng tất cả", stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        sessions.keys.toList().forEach { code -> sessions[code]?.let { teardownSession(it, finalizeRecording = true) } }
        sessions.clear()
        peerFactory?.dispose()
        peerFactory = null
        eglBase.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_ALL = "action_stop_all"
        private const val NOTIF_ID = 44
        private const val BASE_RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
