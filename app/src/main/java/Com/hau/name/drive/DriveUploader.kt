package Com.hau.name.drive

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DriveUploader"
private const val MAX_ATTEMPTS_PER_FILE = 6

/** Định dạng thời điểm ghi gắn vào tên file trên Drive — dùng để ghép đúng hàng theo thời gian
 *  thực khi xem lại (xem VideoIndexer.buildTimeAlignedRows), KHÔNG dùng giờ Drive ghi nhận lúc
 *  upload (giờ upload trễ hơn giờ ghi thật vài giây tới vài phút tuỳ mạng, không dùng để ghép hàng được). */
private val TIMESTAMP_FMT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

/**
 * Nhận file video đã ghi xong 1 đoạn (15 phút), thử lưu lần lượt vào các tài khoản Drive theo
 * đúng thứ tự trong [DriveAccountManager] (từ trên xuống dưới). Tài khoản nào đang reset hoặc
 * gần đầy (dưới 5% dung lượng trống) bị BỎ QUA, thử tài khoản kế tiếp ngay trong cùng lần thử.
 *
 * File chỉ bị XOÁ khỏi máy xem SAU KHI upload thành công — nếu mất mạng/tất cả tài khoản đều
 * gần đầy, file được giữ nguyên trong [pendingDir] và sẽ được [resumePendingUploads] thử lại
 * mỗi khi service khởi động lại (kể cả sau khi app bị hệ thống dừng/khởi động lại máy) — không
 * có video nào bị mất kể cả khi upload chưa xong.
 */
class DriveUploader(private val context: Context, private val accountManager: DriveAccountManager) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingDir = File(context.getExternalFilesDir(null), "HomeCamera_pending_upload").apply { mkdirs() }

    /**
     * Gọi ngay khi 1 đoạn video 15 phút vừa ghi xong. [cameraLabel]/[slotLetter] dùng để đặt
     * tên file trên Drive theo định dạng "tênCam (A) yyyy-MM-dd_HH-mm-ss.mp4". [recordedAtMs]
     * PHẢI là thời điểm THỰC bắt đầu ghi đoạn này (không phải giờ hiện tại lúc gọi hàm này) —
     * SegmentedRecorder đã căn mốc này theo đúng giờ tường (:00/:15/:30/:45) nên các camera
     * khác nhau ghi cùng khung giờ sẽ ra cùng 1 mốc, ghép đúng hàng khi xem lại.
     */
    fun enqueueUpload(segmentFile: File, cameraLabel: String, slotLetter: Char, recordedAtMs: Long) {
        // Di chuyển vào thư mục hàng đợi bền vững trước — để nếu app bị hệ thống giết giữa
        // chừng, resumePendingUploads() ở lần khởi động sau vẫn tìm thấy và thử lại được.
        val queued = File(pendingDir, encodeQueuedName(cameraLabel, slotLetter, recordedAtMs, segmentFile.name))
        try {
            if (!segmentFile.renameTo(queued)) segmentFile.copyTo(queued, overwrite = true).also { segmentFile.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Không đưa được đoạn video vào hàng đợi upload: ${e.message}")
            return
        }
        scope.launch { uploadWithRetry(queued, cameraLabel, slotLetter, recordedAtMs) }
    }

    /** Gọi 1 lần khi ViewerRecordingService khởi động — thử lại mọi video còn tồn trong hàng đợi. */
    fun resumePendingUploads() {
        val files = pendingDir.listFiles() ?: return
        files.forEach { f ->
            val decoded = decodeQueuedName(f.name) ?: return@forEach
            val (label, slot, recordedAtMs) = decoded
            scope.launch { uploadWithRetry(f, label, slot, recordedAtMs) }
        }
    }

    private suspend fun uploadWithRetry(file: File, cameraLabel: String, slotLetter: Char, recordedAtMs: Long) {
        if (!file.exists()) return
        val fileName = "$cameraLabel ($slotLetter) ${TIMESTAMP_FMT.format(Date(recordedAtMs))}.mp4"
        var attempt = 0
        while (file.exists()) {
            if (tryUploadOnce(file, fileName)) {
                file.delete()
                return
            }
            attempt++
            if (attempt >= MAX_ATTEMPTS_PER_FILE) {
                Log.w(TAG, "Chưa upload được ${file.name} sau $attempt lần thử — giữ lại, sẽ thử tiếp lần sau service khởi động")
                return
            }
            delay(minOf(30_000L * attempt, 5 * 60_000L))
        }
    }

    /** Thử lần lượt từng tài khoản theo đúng thứ tự — trả về true nếu 1 tài khoản nào đó nhận được video. */
    private suspend fun tryUploadOnce(file: File, fileName: String): Boolean {
        val candidates = accountManager.candidatesInOrder()
        if (candidates.isEmpty()) {
            Log.w(TAG, "Chưa có tài khoản Google Drive nào được đăng nhập — video đang chờ trong hàng đợi")
            return false
        }
        for (acc in candidates) {
            if (acc.isNearFullCached()) continue // cache cho biết gần đầy rồi, khỏi tốn 1 lượt gọi mạng, thử tk kế
            try {
                val token = DriveRest.getAccessToken(context, acc.email)

                val (used, total) = DriveRest.getQuota(token)
                accountManager.updateQuota(acc.email, used, total)
                val freeFraction = if (total <= 0 || total == Long.MAX_VALUE) 1f else (total - used).toFloat() / total
                if (freeFraction < NEAR_FULL_FREE_FRACTION) continue // gần đầy -> thử tài khoản kế tiếp ngay

                val folderId = DriveRest.getOrCreateAppFolder(token, acc.folderId)
                accountManager.setFolderId(acc.email, folderId)

                DriveRest.uploadResumable(token, folderId, fileName, file)
                Log.d(TAG, "Đã lưu $fileName vào Drive của ${acc.email}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi khi thử lưu qua tài khoản ${acc.email}: ${e.message} — thử tài khoản kế tiếp")
            }
        }
        return false
    }

    companion object {
        /** Mã hoá tên cam + slot + thời điểm ghi thực vào tên file hàng đợi, để đọc lại được sau khi service khởi động lại. */
        private fun encodeQueuedName(cameraLabel: String, slotLetter: Char, recordedAtMs: Long, originalName: String): String {
            val safeLabel = cameraLabel.replace("[|_]".toRegex(), " ")
            return "${System.currentTimeMillis()}_|${safeLabel}|_${slotLetter}_${recordedAtMs}_$originalName"
        }

        private fun decodeQueuedName(name: String): Triple<String, Char, Long>? {
            val m = Regex("""^\d+_\|(.*)\|_([A-Z])_(\d+)_.*$""").find(name) ?: return null
            val (label, slot, recordedAtMs) = m.destructured
            return Triple(label, slot[0], recordedAtMs.toLongOrNull() ?: 0L)
        }
    }
}
