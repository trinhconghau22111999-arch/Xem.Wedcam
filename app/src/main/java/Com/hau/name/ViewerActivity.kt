package Com.hau.name

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import Com.hau.name.drive.DRIVE_SCOPE
import Com.hau.name.drive.DriveAccount
import Com.hau.name.drive.MAX_DRIVE_ACCOUNTS
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Máy A (máy xem camera).
 * - Có thể thêm tối đa [MAX_CAMERAS] Máy Camera khác nhau (mỗi máy 1 mã 6 số riêng), xem
 *   hình trực tiếp cùng lúc, mỗi camera có 1 chỗ cố định (A)/(B)/(C)/(D).
 * - Trong số đó, tối đa [MAX_RECORDING_CAMERAS] máy được phép BẬT GHI HÌNH (ghi liên tục,
 *   cắt đoạn 15 phút); các camera còn lại chỉ xem trực tiếp.
 * - KHÔNG lưu video trên máy xem — mỗi đoạn ghi xong được lưu luân phiên vào các tài khoản
 *   Google Drive đã đăng nhập bên dưới danh sách camera (tối đa 20 tài khoản).
 * - Toàn bộ kết nối + ghi hình chạy trong [ViewerRecordingService] ở nền, không phụ thuộc
 *   Activity đang mở hay đã thoát.
 * - KHÔNG có bất kỳ thao tác điều khiển nào được gửi sang Máy Camera — chỉ xem và ghi.
 */
class ViewerActivity : AppCompatActivity(), ViewerRecordingService.Listener {

    private var service: ViewerRecordingService? = null
    private var bound = false

    private lateinit var listContainer: android.widget.LinearLayout
    private lateinit var textCount: TextView
    private lateinit var btnGrid: Button
    /** false = danh sách dọc (mặc định), true = lưới 2 cột x 2 dòng (tối đa 4 camera 1 màn hình). */
    private var gridMode = false

    private lateinit var driveAccountsContainer: LinearLayout
    private lateinit var textDriveSummary: TextView
    private val driveRefreshHandler = Handler(Looper.getMainLooper())
    private val driveRefreshRunnable = object : Runnable {
        override fun run() {
            refreshDriveAccountsUI()
            driveRefreshHandler.postDelayed(this, 20_000L)
        }
    }

    private val driveSignInClient by lazy {
        GoogleSignIn.getClient(
            this,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DRIVE_SCOPE))
                .build()
        )
    }

    private val driveSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val email = account.email
            val svc = service
            if (email == null || svc == null) return@registerForActivityResult
            val added = svc.getDriveAccountManager().addAccount(email)
            val already = svc.getDriveAccountManager().listAccounts().any { it.email == email }
            when {
                added -> Toast.makeText(this, "Đã thêm tài khoản Drive: $email", Toast.LENGTH_SHORT).show()
                already -> Toast.makeText(this, R.string.drive_account_already_added, Toast.LENGTH_LONG).show()
                else -> Toast.makeText(this, R.string.drive_max_accounts_reached, Toast.LENGTH_LONG).show()
            }
            refreshDriveAccountsUI()
        } catch (e: ApiException) {
            Toast.makeText(this, "Đăng nhập Google thất bại (mã ${e.statusCode})", Toast.LENGTH_LONG).show()
        }
    }

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
            refreshDriveAccountsUI()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        listContainer = findViewById(R.id.camera_list_container)
        textCount = findViewById(R.id.text_camera_count)
        findViewById<Button>(R.id.btn_add_camera).setOnClickListener { showAddCameraDialog() }
        btnGrid = findViewById(R.id.btn_grid_2x2)
        btnGrid.setOnClickListener {
            gridMode = !gridMode
            btnGrid.setText(if (gridMode) R.string.btn_grid_list else R.string.btn_grid_2x2)
            applyLayoutMode()
        }

        driveAccountsContainer = findViewById(R.id.drive_accounts_container)
        textDriveSummary = findViewById(R.id.text_drive_summary)
        findViewById<Button>(R.id.btn_add_drive_account).setOnClickListener { startAddDriveAccount() }
        findViewById<Button>(R.id.btn_drive_reset_settings).setOnClickListener { showDriveResetSettingsDialog() }
        findViewById<Button>(R.id.btn_open_gallery).setOnClickListener {
            startActivity(Intent(this, VideoGalleryActivity::class.java))
        }

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
                    val slot = svc.listCameras().firstOrNull { it.roomCode == code }?.slot ?: 0
                    addTileFor(code, label, slot)
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
            addTileFor(summary.roomCode, summary.label, summary.slot, summary.recordingEnabled)
        }
        updateCountLabel()
        applyLayoutMode()
    }

    private fun addTileFor(code: String, label: String, slot: Int, recordingEnabled: Boolean = false) {
        val svc = service ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.item_camera_tile, listContainer, false)
        val textLabel = view.findViewById<TextView>(R.id.text_label)
        val textStatus = view.findViewById<TextView>(R.id.text_status)
        val previewContainer = view.findViewById<FrameLayout>(R.id.preview_container)
        val checkboxRecording = view.findViewById<CheckBox>(R.id.checkbox_recording)
        val btnRemove = view.findViewById<Button>(R.id.btn_remove)

        // Tên hiển thị theo đúng yêu cầu: "tên (A)" — chữ cái (A)/(B)/(C)/(D) cố định theo chỗ
        // của camera này, dùng luôn để đặt tên file video khi lưu lên Drive (xem slotLetter()).
        textLabel.text = "$label (${slotLetter(slot)})"
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
            tiles.remove(code)
            updateCountLabel()
            applyLayoutMode()
        }

        tiles[code] = TileViews(view, renderer, textStatus, checkboxRecording)
        applyLayoutMode()
    }

    /**
     * Sắp lại các ô camera đã có (giữ nguyên renderer/preview đang chạy, chỉ đổi ViewGroup cha):
     * - Danh sách dọc (mặc định): mỗi camera 1 hàng, full chiều ngang.
     * - Lưới 2x2: gộp từng 2 camera 1 hàng ngang, tối đa 2 hàng (4 camera) hiện gọn 1 màn hình,
     *   camera thứ 5 trở đi (nếu có) tự xuống hàng kế tiếp, cuộn thêm để xem.
     */
    private fun applyLayoutMode() {
        listContainer.removeAllViews()
        val roots = tiles.values.map { it.root }
        val previewHeightPx = (if (gridMode) 150 else 180) * resources.displayMetrics.density
        tiles.values.forEach { tv ->
            (tv.root.findViewById<FrameLayout>(R.id.preview_container)).layoutParams =
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, previewHeightPx.toInt())
        }

        if (!gridMode) {
            roots.forEach { root ->
                (root.parent as? android.view.ViewGroup)?.removeView(root)
                root.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                listContainer.addView(root)
            }
            return
        }

        // Lưới 2x2: gộp từng cặp camera vào 1 hàng ngang, mỗi ô chiếm nửa chiều ngang.
        var i = 0
        while (i < roots.size) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (col in 0 until 2) {
                if (i >= roots.size) break
                val root = roots[i]
                (root.parent as? android.view.ViewGroup)?.removeView(root)
                root.layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { if (col == 0) marginEnd = (4 * resources.displayMetrics.density).toInt() }
                row.addView(root)
                i++
            }
            listContainer.addView(row)
        }
    }

    private fun updateCountLabel() {
        val svc = service ?: return
        textCount.text = "${svc.listCameras().size}/$MAX_CAMERAS camera · ${svc.recordingCount()}/$MAX_RECORDING_CAMERAS đang ghi hình"
    }

    // ---- Tài khoản Google Drive (lưu video luân phiên) ----

    private fun startAddDriveAccount() {
        val svc = service ?: return
        if (svc.getDriveAccountManager().listAccounts().size >= MAX_DRIVE_ACCOUNTS) {
            Toast.makeText(this, R.string.drive_max_accounts_reached, Toast.LENGTH_LONG).show()
            return
        }
        // signOut() trước để hộp thoại chọn tài khoản luôn hiện ra (cho phép chọn 1 tài khoản
        // Google KHÁC với lần trước) thay vì tự động dùng lại tài khoản đã đăng nhập gần nhất.
        driveSignInClient.signOut().addOnCompleteListener {
            driveSignInLauncher.launch(driveSignInClient.signInIntent)
        }
    }

    private fun refreshDriveAccountsUI() {
        val svc = service ?: return
        val accounts = svc.getDriveAccountManager().listAccounts() // đã sắp theo order (trên xuống dưới)
        driveAccountsContainer.removeAllViews()
        accounts.forEachIndexed { index, acc -> driveAccountsContainer.addView(buildDriveAccountChip(acc, index)) }
        textDriveSummary.text = "${accounts.size}/$MAX_DRIVE_ACCOUNTS tài khoản đã đăng nhập"
    }

    private fun buildDriveAccountChip(acc: DriveAccount, indexInOrder: Int): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_drive_account, driveAccountsContainer, false)
        val textOrder = view.findViewById<TextView>(R.id.text_drive_order)
        val textEmail = view.findViewById<TextView>(R.id.text_drive_email)
        val textStatus = view.findViewById<TextView>(R.id.text_drive_status)
        val btnRemove = view.findViewById<Button>(R.id.btn_drive_remove)

        textOrder.text = when {
            acc.isResetting -> "#${indexInOrder + 1} (đang xoá bớt...)"
            indexInOrder == 0 -> "#1 (đang lưu vào đây)"
            else -> "#${indexInOrder + 1}"
        }
        textEmail.text = acc.email
        textStatus.text = when {
            acc.totalBytes <= 0 -> "Chưa kiểm tra dung lượng"
            acc.totalBytes == Long.MAX_VALUE -> "Không giới hạn dung lượng"
            else -> {
                val pct = (acc.usedBytes * 100 / acc.totalBytes).coerceIn(0, 100)
                "Đã dùng $pct%" + if (acc.isNearFullCached()) " · gần đầy" else ""
            }
        }
        btnRemove.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Gỡ tài khoản Drive")
                .setMessage("Gỡ ${acc.email} khỏi danh sách lưu video? (Video đã lưu trong Drive tài khoản này KHÔNG bị xoá.)")
                .setPositiveButton("Gỡ") { _, _ ->
                    service?.getDriveAccountManager()?.removeAccount(acc.email)
                    refreshDriveAccountsUI()
                }
                .setNegativeButton("Huỷ", null)
                .show()
        }
        return view
    }

    private fun showDriveResetSettingsDialog() {
        val svc = service ?: return
        val mgr = svc.getDriveAccountManager()
        val editDays = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(mgr.resetIntervalDays.toString())
            hint = "Số ngày (0 = tắt tự xoá)"
        }
        AlertDialog.Builder(this)
            .setTitle("Tự xoá video để giải phóng bộ nhớ")
            .setMessage("Sau bao nhiêu ngày thì tự xoá hết video của 1 tài khoản Drive để lấy lại chỗ trống? Tài khoản đó sẽ được chuyển xuống cuối danh sách sau khi xoá xong. Đặt 0 để tắt tính năng này.")
            .setView(editDays)
            .setPositiveButton("Lưu") { _, _ ->
                val days = editDays.text.toString().toIntOrNull() ?: 0
                mgr.resetIntervalDays = days
                Toast.makeText(this, "Đã lưu: $days ngày", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Huỷ", null)
            .show()
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
        driveRefreshHandler.removeCallbacks(driveRefreshRunnable)
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            bindService(Intent(this, ViewerRecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
            bound = true
        }
        driveRefreshHandler.post(driveRefreshRunnable)
    }

    override fun onDestroy() {
        tiles.values.forEach { it.renderer.release() }
        super.onDestroy()
    }
}
