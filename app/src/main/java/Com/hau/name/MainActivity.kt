package Com.hau.name

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Màn hình đầu tiên: chọn vai trò của máy này.
 * - "Máy Camera" (Máy B, điện thoại cũ đặt cố định trong nhà) -> CameraActivity
 * - "Máy Xem" (Máy A, điện thoại bạn dùng để xem lại)          -> ViewerActivity
 *
 * Vai trò đã chọn được LƯU LẠI VĨNH VIỄN cho máy này (persistDevice) — từ lần mở app
 * sau, màn hình này sẽ tự động đi thẳng vào đúng trang đã chọn, không hiện lại 2 nút
 * lựa chọn nữa, và KHÔNG có cách nào đổi lại từ trong app (khoá tuyệt đối theo yêu cầu).
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val role = rolePrefs().getString(KEY_ROLE, null)
        if (role == ROLE_CAMERA) {
            startActivity(Intent(this, CameraActivity::class.java)); finish(); return
        } else if (role == ROLE_VIEWER) {
            startActivity(Intent(this, ViewerActivity::class.java)); finish(); return
        }

        setContentView(R.layout.activity_main)

        findViewById<android.widget.Button>(R.id.btn_role_camera).setOnClickListener {
            rolePrefs().edit().putString(KEY_ROLE, ROLE_CAMERA).apply()
            startActivity(Intent(this, CameraActivity::class.java))
            finish()
        }
        findViewById<android.widget.Button>(R.id.btn_role_viewer).setOnClickListener {
            rolePrefs().edit().putString(KEY_ROLE, ROLE_VIEWER).apply()
            startActivity(Intent(this, ViewerActivity::class.java))
            finish()
        }
    }

    private fun rolePrefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "device_role"
        const val KEY_ROLE = "role"
        const val ROLE_CAMERA = "camera"
        const val ROLE_VIEWER = "viewer"
    }
}
