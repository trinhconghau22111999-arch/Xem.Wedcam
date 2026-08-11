package Com.hau.name

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Máy A (máy xem camera).
 * - Có thể thêm tối đa [MAX_CAMERAS] Máy Camera khác nhau (mỗi máy 1 mã 6 số riêng), xem
 *   hình trực tiếp cùng lúc.
 * - Trong số đó, tối đa [MAX_RECORDING_CAMERAS] máy được phép BẬT GHI HÌNH (ghi liên tục,
 *   cắt đoạn 30 phút, lưu vào Thư viện); các camera còn lại chỉ xem trực tiếp.
 * - Toàn bộ kết nối + ghi hình chạy trong [ViewerRecordingService] ở nền, không phụ thuộc
 *   Activity đang mở hay đã thoát.
 * - KHÔNG có bất kỳ thao tác điều khiển nào được gửi sang Máy Camera — chỉ xem và ghi.
 */
class ViewerActivity : AppCompatActivity(), ViewerRecordingService.Listener {

    private var service: ViewerRecordingService? = null
    private var bound = false

    private lateinit var listContainer: android.widget.LinearLayout
    private lateinit var textCount: TextView

    // roomCode -> views của ô camera tương ứng, để cập nhật khi có thay đổi trạng thái
    private data class TileViews(
        val root: View,
        val renderer: SurfaceViewRenderer,
        val textStatus: TextView,
        val checkboxRecording: CheckBox
    )
    private val tiles = LinkedHashMap<String, TileViews>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as ViewerRecordingService.LocalBinder).getService()
            service = svc
            svc.listener = this@ViewerActivity
            rebuildAllTiles()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        listContainer = findViewById(R.id.camera_list_container)
        textCount = findViewById(R.id.text_camera_count)
        findViewById<Button>(R.id.btn_add_camera).setOnClickListener { showAddCameraDialog() }

        // Khởi động (nếu chưa chạy) + bind vào service ghi hình nền — service tồn tại độc
        // lập với Activity nên nếu đã có camera đang chạy từ trước, ta chỉ cần bind lại.
        val intent = Intent(this, ViewerRecordingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    private fun showAddCameraDialog() {
        val svc = service ?: return
        if (svc.listCameras().size >= MAX_CAMERAS) {
            Toast.makeText(this, "Đã đạt tối đa $MAX_CAMERAS camera", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_camera, null)
        val editCode = dialogView.findViewById<EditText>(R.id.edit_pairing_code)
        val editLabel = dialogView.findViewById<EditText>(R.id.edit_camera_label)

        AlertDialog.Builder(this)
            .setTitle(R.string.btn_add_camera)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_connect) { _, _ ->
                val code = editCode.text.toString().trim()
                if (code.length != 6) {
                    Toast.makeText(this, "Mã phải gồm 6 chữ số", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val label = editLabel.text.toString().trim().ifEmpty { "Camera $code" }
                // Thêm camera ở chế độ chỉ xem trực tiếp trước — muốn ghi hình thì tick
                // ô "Lưu video" ngay trên ô camera đó sau khi đã thêm (chỉ 1 chỗ duy nhất).
                val error = svc.addCamera(code, label, wantRecording = false)
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                } else {
                    addTileFor(code, label)
                    updateCountLabel()
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun rebuildAllTiles() {
        val svc = service ?: return
        listContainer.removeAllViews()
        tiles.clear()
        svc.listCameras().forEach { summary ->
            addTileFor(summary.roomCode, summary.label, summary.recordingEnabled)
        }
        updateCountLabel()
    }

    private fun addTileFor(code: String, label: String, recordingEnabled: Boolean = false) {
        val svc = service ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.item_camera_tile, listContainer, false)
        val textLabel = view.findViewById<TextView>(R.id.text_label)
        val textStatus = view.findViewById<TextView>(R.id.text_status)
        val previewContainer = view.findViewById<FrameLayout>(R.id.preview_container)
        val checkboxRecording = view.findViewById<CheckBox>(R.id.checkbox_recording)
        val btnRemove = view.findViewById<Button>(R.id.btn_remove)

        textLabel.text = "$label ($code)"
        textStatus.text = "Đang kết nối..."

        val renderer = SurfaceViewRenderer(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        previewContainer.addView(renderer)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.init(svc.getEglBaseContext(), null)
        svc.attachPreview(code, renderer)

        checkboxRecording.isChecked = recordingEnabled
        checkboxRecording.setOnCheckedChangeListener { _, isChecked ->
            val err = service?.setRecordingEnabled(code, isChecked)
            if (err != null) {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                checkboxRecording.setOnCheckedChangeListener(null)
                checkboxRecording.isChecked = !isChecked
                checkboxRecording.setOnCheckedChangeListener { _, checked -> service?.setRecordingEnabled(code, checked) }
            }
        }

        btnRemove.setOnClickListener {
            service?.detachPreview(code, renderer)
            service?.removeCamera(code)
            renderer.release()
            listContainer.removeView(view)
            tiles.remove(code)
            updateCountLabel()
        }

        listContainer.addView(view)
        tiles[code] = TileViews(view, renderer, textStatus, checkboxRecording)
    }

    private fun updateCountLabel() {
        val svc = service ?: return
        textCount.text = "${svc.listCameras().size}/$MAX_CAMERAS camera · ${svc.recordingCount()}/$MAX_RECORDING_CAMERAS đang ghi hình"
    }

    override fun onCameraStateChanged(roomCode: String, state: CameraConnState, message: String?) {
        runOnUiThread {
            val tile = tiles[roomCode] ?: return@runOnUiThread
            tile.textStatus.text = when (state) {
                CameraConnState.CONNECTING -> "Đang kết nối..."
                CameraConnState.CONNECTED -> "Đã kết nối — đang xem trực tiếp"
                CameraConnState.RECONNECTING -> (message ?: "Mất kết nối") + " — đang tự kết nối lại..."
                CameraConnState.DISCONNECTED -> message ?: "Đã ngắt kết nối"
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Chỉ gỡ preview (đỡ tốn GPU khi app ở nền) + unbind — KHÔNG dừng service,
        // để các camera vẫn tiếp tục kết nối/ghi hình khi thoát màn hình xem.
        service?.let { svc -> tiles.forEach { (code, tile) -> svc.detachPreview(code, tile.renderer) } }
        if (bound) { unbindService(connection); bound = false }
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            bindService(Intent(this, ViewerRecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
            bound = true
        }
    }

    override fun onDestroy() {
        tiles.values.forEach { it.renderer.release() }
        super.onDestroy()
    }
}
