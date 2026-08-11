package Com.hau.name

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Tự động khởi động lại camera khi:
 * - Máy khởi động xong (BOOT_COMPLETED) — vd. sau khi mất điện rồi có điện lại, người cắm sạc
 *   bật nguồn máy lên bằng tay.
 * - App vừa được cập nhật (MY_PACKAGE_REPLACED) — để không phải mở tay lại sau khi tự cập nhật.
 *
 * CHỈ áp dụng cho máy đang đóng vai trò CAMERA (đã từng bấm "Bắt đầu làm Camera" ít nhất 1 lần,
 * tức đã có mã cố định lưu trong SharedPreferences) — máy xem (Máy A) không cần tự bật lại vì
 * người dùng phải chủ động mở app để xem.
 *
 * Android CHO PHÉP khởi động foreground service ngay từ BroadcastReceiver phản hồi
 * BOOT_COMPLETED (đây là 1 trong các trường hợp ngoại lệ được phép start-from-background).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.MY_PACKAGE_REPLACED"
        ) return

        val prefs = context.getSharedPreferences(CameraActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val fixedCode = prefs.getString(CameraActivity.KEY_FIXED_CODE, null) ?: return

        val serviceIntent = Intent(context, CameraStreamService::class.java).apply {
            putExtra(CameraStreamService.EXTRA_ROOM_CODE, fixedCode)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
