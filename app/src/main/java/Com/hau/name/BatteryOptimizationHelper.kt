package Com.hau.name

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

/**
 * Nhiều dòng máy (Xiaomi/MIUI, Oppo/ColorOS, Vivo...) tự ý "dọn" ứng dụng chạy nền rất mạnh tay,
 * dễ giết chết Foreground Service đang ghi hình/upload dù đã có thông báo liên tục — nhất là khi
 * màn hình tắt lâu. Xin người dùng loại trừ app khỏi tối ưu pin giúp giảm hẳn tình trạng này
 * (không đảm bảo 100% với các máy có thêm lớp quản lý pin riêng ngoài Android gốc, nhưng luôn
 * nên bật).
 */
object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Mở thẳng hộp thoại hệ thống xin loại trừ app này khỏi tối ưu pin. */
    fun requestIgnore(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            // Vài hãng (đặc biệt bản MIUI/ColorOS cũ) chặn intent này -> mở màn hình cài đặt pin
            // chung của hệ thống để người dùng tự tìm app và tắt tối ưu pin thủ công.
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                Toast.makeText(activity, "Tìm app này trong danh sách rồi tắt tối ưu pin giúp", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                Toast.makeText(activity, "Máy này không hỗ trợ mở cài đặt pin trực tiếp — vào Cài đặt > Pin > tìm app này để tắt thủ công", Toast.LENGTH_LONG).show()
            }
        }
    }
}
