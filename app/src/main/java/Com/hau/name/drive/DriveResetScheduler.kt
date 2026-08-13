package Com.hau.name.drive

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "DriveRetentionCleaner"
private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L // quét mỗi 30 phút là đủ, không cần chính xác từng giây

/**
 * Dọn video QUÁ HẠN theo đúng TUỔI CỦA TỪNG VIDEO (không phải theo lịch của cả tài khoản):
 * cứ mỗi [CHECK_INTERVAL_MS], quét toàn bộ video trong MỌI tài khoản Drive đã đăng nhập, video
 * nào đã ghi được quá [DriveAccountManager.retentionDays] ngày (tính từ thời điểm ghi thực,
 * đọc từ tên file — xem [VideoIndexer.extractRecordedAtMs]) thì bị xoá VĨNH VIỄN, các video
 * còn trong hạn của tài khoản đó không bị đụng tới.
 *
 * retentionDays = 0 -> tắt hẳn tính năng dọn tự động (video giữ tới khi tài khoản đầy dung
 * lượng thật, lúc đó [DriveUploader] tự chuyển sang tài khoản kế tiếp — không xoá gì).
 *
 * Vì đây là dọn dần liên tục theo tuổi video (không phải "xoá sạch 1 lần rồi xong"), tài khoản
 * KHÔNG cần bị tạm khoá ghi trong lúc dọn, và cũng không cần đổi thứ tự ưu tiên của tài khoản —
 * chỗ trống được giải phóng dần một cách tự nhiên.
 */
class DriveResetScheduler(private val context: Context, private val accountManager: DriveAccountManager) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    /** Tránh quét trùng 1 tài khoản trong lúc lượt quét trước còn đang chạy dở (mạng chậm). */
    private val cleaningNow = HashSet<String>()

    private val loop = object : Runnable {
        override fun run() {
            runCleanupPass()
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

    private fun runCleanupPass() {
        val days = accountManager.retentionDays
        if (days <= 0) return
        val cutoffMs = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        accountManager.listAccounts().forEach { acc ->
            if (!cleaningNow.add(acc.email)) return@forEach // đang quét tài khoản này rồi, bỏ qua lượt này
            scope.launch {
                try {
                    cleanupAccount(acc.email, cutoffMs)
                } finally {
                    cleaningNow.remove(acc.email)
                }
            }
        }
    }

    private suspend fun cleanupAccount(email: String, cutoffMs: Long) {
        try {
            val token = DriveRest.getAccessToken(context, email)
            val acc = accountManager.listAccounts().firstOrNull { it.email == email }
            val folderId = DriveRest.getOrCreateAppFolder(token, acc?.folderId)
            accountManager.setFolderId(email, folderId)

            var deletedCount = 0
            DriveRest.listVideos(token, folderId).forEach { f ->
                val recordedAtMs = VideoIndexer.extractRecordedAtMs(f.name, f.createdTimeMs)
                if (recordedAtMs in 1 until cutoffMs) {
                    try {
                        DriveRest.deleteFile(token, f.id)
                        deletedCount++
                    } catch (e: Exception) {
                        Log.w(TAG, "Không xoá được video quá hạn ${f.name} ($email): ${e.message}")
                    }
                }
            }
            if (deletedCount > 0) Log.d(TAG, "Đã dọn $deletedCount video quá hạn ở tài khoản $email")
        } catch (e: Exception) {
            Log.w(TAG, "Dọn video quá hạn cho $email thất bại, sẽ thử lại ở lượt quét sau: ${e.message}")
        }
    }
}
