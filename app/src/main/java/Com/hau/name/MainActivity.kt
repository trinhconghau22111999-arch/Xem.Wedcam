package Com.hau.name

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Màn hình đầu tiên: chọn vai trò của máy này.
 * - "Máy Camera" (Máy B, điện thoại cũ đặt cố định trong nhà) -> CameraActivity
 * - "Máy Xem" (Máy A, điện thoại bạn dùng để xem lại)          -> ViewerActivity
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<android.widget.Button>(R.id.btn_role_camera).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }
        findViewById<android.widget.Button>(R.id.btn_role_viewer).setOnClickListener {
            startActivity(Intent(this, ViewerActivity::class.java))
        }
    }
}
