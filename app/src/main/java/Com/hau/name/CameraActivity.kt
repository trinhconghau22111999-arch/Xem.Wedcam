package Com.hau.name

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Máy B (điện thoại cũ đóng vai trò camera giám sát, đặt cố định trong nhà).
 *
 * Luồng bắt buộc:
 * 1. Người dùng tự tick đồng ý -> nút "Bắt đầu làm camera" mới bật.
 * 2. Bấm nút sẽ xin quyền CAMERA (hộp thoại hệ thống, không tùy biến được).
 * 3. Sau khi cấp quyền, tạo mã ghép nối 6 số ngẫu nhiên, ghi lên Firebase,
 *    rồi khởi động CameraStreamService (foreground service) để bật camera sau
 *    và bắt đầu truyền hình ảnh thời gian thực qua WebRTC.
 * 4. Máy A (máy xem) phải nhập ĐÚNG mã 6 số này mới xem được — máy B không
 *    tự kết nối hay hiện hình cho bất kỳ ai không có mã.
 * 5. Máy B KHÔNG nhận bất kỳ lệnh điều khiển nào từ máy A (không có kênh
 *    input injection) — chỉ một chiều: quay và gửi hình đi.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var checkboxConsent: CheckBox
    private lateinit var btnStart: Button
    private lateinit var layoutPairingCode: android.widget.LinearLayout
    private lateinit var textPairingCode: TextView
    private lateinit var btnEndSession: Button

    private var roomCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        checkboxConsent = findViewById(R.id.checkbox_consent)
        btnStart = findViewById(R.id.btn_generate_code)
        layoutPairingCode = findViewById(R.id.layout_pairing_code)
        textPairingCode = findViewById(R.id.text_pairing_code)
        btnEndSession = findViewById(R.id.btn_end_session)

        checkboxConsent.setOnCheckedChangeListener { _, isChecked ->
            btnStart.isEnabled = isChecked
        }
        btnStart.isEnabled = false

        btnStart.setOnClickListener { requestCameraPermissionThenStart() }
        btnEndSession.setOnClickListener { endSession() }

        // Nếu service camera đang chạy sẵn (vd. quay lại màn hình sau khi thoát app),
        // hiển thị lại mã đang hoạt động thay vì bắt bấm lại từ đầu.
        restoreActiveSessionIfAny()
    }

    private fun restoreActiveSessionIfAny() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val fixedCode = prefs.getString(KEY_FIXED_CODE, null) ?: return
        roomCode = fixedCode
        checkboxConsent.isChecked = true
        textPairingCode.text = fixedCode
        layoutPairingCode.visibility = android.view.View.VISIBLE
    }

    private fun requestCameraPermissionThenStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            generatePairingCodeAndStartService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        val cameraIndex = permissions.indexOf(Manifest.permission.CAMERA)
        val cameraGranted = cameraIndex == -1 || grantResults.getOrNull(cameraIndex) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            generatePairingCodeAndStartService()
        } else {
            Toast.makeText(this, "Cần quyền Camera để dùng máy này làm camera giám sát", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Mã ghép nối giờ CỐ ĐỊNH theo từng máy — chỉ sinh ngẫu nhiên 1 LẦN DUY NHẤT khi máy này
     * lần đầu được dùng làm camera, sau đó lưu lại vĩnh viễn và tái sử dụng mỗi lần mở app
     * (kể cả sau khi "Kết thúc phiên" hay khởi động lại máy) — không đổi mã nữa.
     */
    private fun generatePairingCodeAndStartService() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val code = prefs.getString(KEY_FIXED_CODE, null) ?: run {
            val newCode = (100000..999999).random().toString()
            prefs.edit().putString(KEY_FIXED_CODE, newCode).apply()
            newCode
        }
        roomCode = code

        val serviceIntent = Intent(this, CameraStreamService::class.java).apply {
            putExtra(CameraStreamService.EXTRA_ROOM_CODE, code)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        com.google.firebase.database.FirebaseDatabase.getInstance().reference
            .child("rooms").child(code).setValue(
                mapOf("status" to "waiting", "consentGivenAt" to System.currentTimeMillis())
            ).addOnFailureListener { e ->
                Toast.makeText(this, "Không thể ghi trạng thái phòng lên máy chủ: ${e.message}",
                    Toast.LENGTH_LONG).show()
            }

        textPairingCode.text = code
        layoutPairingCode.visibility = android.view.View.VISIBLE

        requestIgnoreBatteryOptimizations()
    }

    /**
     * Xin loại trừ khỏi tối ưu pin (Doze/App Standby) — nếu không xin, một số hãng máy
     * (Xiaomi, Oppo, Samsung...) có thể tự tắt app nền sau vài giờ kể cả khi màn hình tắt,
     * làm gián đoạn camera. Đây là hộp thoại hệ thống, người dùng có thể từ chối.
     */
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {
                Toast.makeText(this,
                    "Vui lòng vào Cài đặt > Pin > gỡ giới hạn nền cho app này để camera chạy ổn định khi tắt màn hình",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Kết thúc phiên: dừng camera, nhưng GIỮ NGUYÊN mã cố định để lần sau dùng lại được. */
    private fun endSession() {
        roomCode?.let { code ->
            com.google.firebase.database.FirebaseDatabase.getInstance().reference
                .child("rooms").child(code).child("status").setValue("ended")
        }
        stopService(Intent(this, CameraStreamService::class.java))
        layoutPairingCode.visibility = android.view.View.GONE
        checkboxConsent.isChecked = false
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 2001
        const val PREFS_NAME = "home_camera"
        /** Mã ghép nối cố định của máy này — sinh 1 lần, dùng mãi mãi. */
        const val KEY_FIXED_CODE = "fixed_room_code"
    }
}
