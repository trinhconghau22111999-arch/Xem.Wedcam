package Com.hau.name.drive

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "DriveResetScheduler"
private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L // kiểm tra mỗi 30 phút là đủ, không cần chính xác từng giây

/**
 * Cứ mỗi [DriveAccountManager.resetIntervalDays] ngày (tính từ lần đăng nhập/reset gần nhất),
 * XOÁ TOÀN BỘ video đang lưu trong tài khoản đó để giải phóng bộ nhớ, rồi đẩy tài khoản này
 * xuống CUỐI thứ tự luân phiên (các tài khoản còn dung lượng khác sẽ được ưu tiên lưu trước).
 * Trong lúc đang xoá (isResetting = true), tài khoản đó KHÔNG được chọn để lưu video mới
 * (xem [DriveAccountManager.candidatesInOrder] và [DriveUploader]).
 *
 * resetIntervalDays = 0 -> tắt hẳn tính năng tự động reset (video chỉ ngừng lưu vào 1 tài khoản
 * khi tài khoản đó tự nhiên gần đầy dung lượng thật).
 */
class DriveResetScheduler(private val context: Context, private val accountManager: DriveAccountManager) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private val loop = object : Runnable {
        override fun run() {
            checkAll()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        handler.post(loop)
    }

    fun stop() {
        started = false
        handler.removeCallbacks(loop)
    }

    private fun checkAll() {
        val intervalDays = accountManager.resetIntervalDays
        if (intervalDays <= 0) return
        val intervalMs = intervalDays * 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        accountManager.listAccounts().forEach { acc ->
            if (acc.isResetting) return@forEach
            if (acc.lastResetAtMs <= 0) return@forEach
            if (now - acc.lastResetAtMs >= intervalMs) {
                scope.launch { performReset(acc.email) }
            }
        }
    }

    /** Có thể gọi thủ công (vd. nút "Reset ngay" trong Cài đặt) ngoài chu kỳ tự động. */
    fun resetNow(email: String) {
        scope.launch { performReset(email) }
    }

    private suspend fun performReset(email: String) {
        accountManager.markResetting(email, true)
        try {
            val token = DriveRest.getAccessToken(context, email)
            val acc = accountManager.listAccounts().firstOrNull { it.email == email }
            val folderId = DriveRest.getOrCreateAppFolder(token, acc?.folderId)
            DriveRest.deleteAllFilesInFolder(token, folderId)
            accountManager.completeResetAndMoveToBottom(email)
            Log.d(TAG, "Đã reset xong tài khoản $email, chuyển xuống cuối danh sách")
        } catch (e: Exception) {
            Log.w(TAG, "Reset tài khoản $email thất bại, sẽ thử lại ở lần kiểm tra sau: ${e.message}")
            accountManager.markResetting(email, false)
        }
    }
}
