package Com.hau.name.drive

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "VideoIndexer"

/** Kích thước 1 mốc thời gian để ghép hàng — khớp với thời lượng 1 đoạn video (15 phút). */
private const val TIME_SLOT_MS = 15 * 60 * 1000L

data class VideoEntry(
    val accountEmail: String,
    val fileId: String,
    val fileName: String,
    val cameraLabel: String,
    val slotLetter: Char, // 'A'..'D', hoặc '?' nếu tên file không theo đúng định dạng "tên (X)...".
    /** Thời điểm THỰC bắt đầu ghi đoạn này (đọc từ tên file, do máy xem gắn vào lúc ghi) —
     *  dùng để ghép hàng chính xác. Với video cũ tải lên TRƯỚC khi có timestamp trong tên file,
     *  tự động dùng tạm giờ Drive nhận file (kém chính xác hơn, có thể lệch hàng vài phút). */
    val createdTimeMs: Long
)

/** 1 hàng trong bảng — [bucketStartMs] là mốc 15 phút mà hàng này đại diện; [cells] là video
 *  thực tế của từng camera RƠI VÀO đúng mốc 15 phút đó (có thể thiếu cột nếu camera đó không
 *  ghi được đoạn nào trong khung giờ này — do tắt ghi, mất kết nối, hoặc thêm camera sau). */
data class GalleryRow(val bucketStartMs: Long, val cells: Map<Char, VideoEntry>)

/** Đọc toàn bộ video đang lưu trên MỌI tài khoản Drive đã đăng nhập, gộp thành 1 danh sách duy nhất. */
object VideoIndexer {
    // Tên chuẩn (có timestamp lúc ghi, từ bản mới): "Phòng khách (A) 2026-08-13_14-00-00.mp4"
    private val NAME_REGEX_WITH_TS = Regex("""^(.*) \(([A-D])\) (\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})\.\w+$""")
    // Tên cũ (bản trước, không có timestamp): "Phòng khách (A).mp4" — vẫn đọc được để không mất video cũ.
    private val NAME_REGEX_LEGACY = Regex("""^(.*) \(([A-D])\)\.\w+$""")
    private val TS_PARSE_FMT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    suspend fun fetchAll(context: Context, accountManager: DriveAccountManager): List<VideoEntry> {
        val out = ArrayList<VideoEntry>()
        accountManager.listAccounts().forEach { acc ->
            if (acc.isResetting) return@forEach // đang xoá dở, danh sách chưa ổn định, bỏ qua lượt này
            try {
                val token = DriveRest.getAccessToken(context, acc.email)
                val folderId = DriveRest.getOrCreateAppFolder(token, acc.folderId)
                accountManager.setFolderId(acc.email, folderId)
                DriveRest.listVideos(token, folderId).forEach { f -> out += parseEntry(acc.email, f) }
            } catch (e: Exception) {
                Log.w(TAG, "Không lấy được danh sách video của ${acc.email}: ${e.message}")
            }
        }
        return out
    }

    private fun parseEntry(accountEmail: String, f: DriveFileRef): VideoEntry {
        val withTs = NAME_REGEX_WITH_TS.find(f.name)
        if (withTs != null) {
            val (label, slot, ts) = withTs.destructured
            val recordedAtMs = try { TS_PARSE_FMT.parse(ts)?.time } catch (e: Exception) { null } ?: f.createdTimeMs
            return VideoEntry(accountEmail, f.id, f.name, label, slot.first(), recordedAtMs)
        }
        val legacy = NAME_REGEX_LEGACY.find(f.name)
        val label = legacy?.groupValues?.get(1) ?: f.name.substringBeforeLast('.')
        val slot = legacy?.groupValues?.get(2)?.firstOrNull() ?: '?'
        // Video cũ chưa có timestamp trong tên -> đành dùng tạm giờ Drive nhận file lúc upload.
        return VideoEntry(accountEmail, f.id, f.name, label, slot, f.createdTimeMs)
    }

    /**
     * Ghép video thành từng HÀNG theo đúng MỐC THỜI GIAN THỰC lúc bắt đầu ghi (làm tròn xuống
     * mốc 15 phút gần nhất — vì SegmentedRecorder đã tự căn giờ ghi vào đúng lưới :00/:15/:30/:45
     * nên video của các camera KHÁC NHAU ghi CÙNG khung giờ sẽ có cùng mốc, rơi đúng CÙNG 1
     * hàng, đúng cột (A)/(B)/(C)/(D) của camera đó). Hàng mới nhất nằm trên cùng.
     */
    fun buildTimeAlignedRows(entries: List<VideoEntry>): List<GalleryRow> {
        val buckets = LinkedHashMap<Long, MutableMap<Char, VideoEntry>>()
        entries.filter { it.slotLetter in 'A'..'D' && it.createdTimeMs > 0 }.forEach { e ->
            val bucket = e.createdTimeMs - (e.createdTimeMs % TIME_SLOT_MS)
            val row = buckets.getOrPut(bucket) { mutableMapOf() }
            // Cùng 1 camera có 2 video rơi cùng 1 mốc 15 phút (hiếm, lệch giờ hệ thống...) -> giữ bản mới hơn.
            val existing = row[e.slotLetter]
            if (existing == null || e.createdTimeMs > existing.createdTimeMs) row[e.slotLetter] = e
        }
        return buckets.entries.sortedByDescending { it.key }.map { GalleryRow(it.key, it.value) }
    }

    /** Tên gợi nhớ mới nhất của 1 camera theo cột — dùng để hiện ở tiêu đề cột. */
    fun latestLabelForSlot(entries: List<VideoEntry>, slot: Char): String? =
        entries.filter { it.slotLetter == slot }.maxByOrNull { it.createdTimeMs }?.cameraLabel
}
