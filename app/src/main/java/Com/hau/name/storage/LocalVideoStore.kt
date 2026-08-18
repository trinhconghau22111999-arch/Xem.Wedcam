package Com.hau.name.storage

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "LocalVideoStore"

/** Kích thước 1 mốc thời gian để ghép hàng — khớp với thời lượng 1 đoạn video (15 phút). */
private const val TIME_SLOT_MS = 15 * 60 * 1000L

/** Tên thư mục (trong bộ nhớ riêng của app, getExternalFilesDir) nơi TOÀN BỘ video được lưu
 *  vĩnh viễn trên máy xem — không có Drive hay server nào ở giữa, xem trực tiếp từ đây. */
private const val VIDEOS_DIR_NAME = "HomeCamera_videos"

fun videosDir(context: Context): File =
    File(context.getExternalFilesDir(null), VIDEOS_DIR_NAME).apply { mkdirs() }

data class VideoEntry(
    val file: File,
    val cameraLabel: String,
    val slotLetter: Char, // 'A'..'D', hoặc '?' nếu tên file không theo đúng định dạng.
    /** Thời điểm THỰC bắt đầu ghi đoạn này (đọc từ tên file) — dùng để ghép hàng chính xác. */
    val createdTimeMs: Long
)

/** 1 hàng trong bảng — [bucketStartMs] là mốc 15 phút mà hàng này đại diện; [cells] là video
 *  thực tế của từng camera RƠI VÀO đúng mốc 15 phút đó (có thể thiếu cột nếu camera đó không
 *  ghi được đoạn nào trong khung giờ này). */
data class GalleryRow(val bucketStartMs: Long, val cells: Map<Char, VideoEntry>)

/**
 * Thay cho module Drive trước đây: TOÀN BỘ video được ghi thẳng và lưu VĨNH VIỄN ngay trên máy
 * xem (KHÔNG có tài khoản Drive, KHÔNG upload, KHÔNG proxy — xem lại là phát thẳng file cục bộ).
 * Đây là quy tắc gốc ban đầu của dự án.
 */
object LocalVideoStore {
    // "Phòng khách (A) 2026-08-13_14-00-00.mp4"
    private val NAME_REGEX = Regex("""^(.*) \(([A-D])\) (\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})\.\w+$""")
    private val TS_PARSE_FMT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /** Chuẩn hoá tên camera thành phần an toàn của tên file (bỏ ký tự không hợp lệ). */
    fun safeLabel(label: String): String = label.replace(Regex("""[\\/:*?"<>|]"""), " ").trim()

    /** Tên file chuẩn dùng khi ghi 1 đoạn mới — cùng định dạng dùng để đọc lại sau này. */
    fun fileNameFor(cameraLabel: String, slotLetter: Char, recordedAtMs: Long): String {
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(java.util.Date(recordedAtMs))
        return "${safeLabel(cameraLabel)} ($slotLetter) $ts.mp4"
    }

    /** Đọc toàn bộ video đang lưu trên máy — quét thư mục cục bộ, không có gọi mạng nào. */
    suspend fun listAll(context: Context): List<VideoEntry> = withContext(Dispatchers.IO) {
        val files = videosDir(context).listFiles() ?: return@withContext emptyList()
        files.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            .mapNotNull { parseEntry(it) }
    }

    private fun parseEntry(file: File): VideoEntry? {
        val m = NAME_REGEX.find(file.name) ?: return VideoEntry(file, file.nameWithoutExtension, '?', file.lastModified())
        val label = m.groupValues[1]
        val slot = m.groupValues[2].first()
        val ts = m.groupValues[3]
        val createdAtMs = try { TS_PARSE_FMT.parse(ts)?.time } catch (e: Exception) { null } ?: file.lastModified()
        return VideoEntry(file, label, slot, createdAtMs)
    }

    /**
     * Ghép video thành từng HÀNG theo đúng MỐC THỜI GIAN THỰC lúc bắt đầu ghi (làm tròn xuống
     * mốc 15 phút gần nhất) — video của các camera KHÁC NHAU ghi CÙNG khung giờ sẽ có cùng mốc,
     * rơi đúng CÙNG 1 hàng, đúng cột (A)/(B)/(C)/(D) của camera đó). Hàng mới nhất nằm trên cùng.
     */
    fun buildTimeAlignedRows(entries: List<VideoEntry>): List<GalleryRow> {
        val buckets = LinkedHashMap<Long, MutableMap<Char, VideoEntry>>()
        entries.filter { it.slotLetter in 'A'..'D' && it.createdTimeMs > 0 }.forEach { e ->
            val bucket = e.createdTimeMs - (e.createdTimeMs % TIME_SLOT_MS)
            val row = buckets.getOrPut(bucket) { mutableMapOf() }
            val existing = row[e.slotLetter]
            if (existing == null || e.createdTimeMs > existing.createdTimeMs) row[e.slotLetter] = e
        }
        return buckets.entries.sortedByDescending { it.key }.map { GalleryRow(it.key, it.value) }
    }

    /** Tên gợi nhớ mới nhất của 1 camera theo cột — dùng để hiện ở tiêu đề cột. */
    fun latestLabelForSlot(entries: List<VideoEntry>, slot: Char): String? =
        entries.filter { it.slotLetter == slot }.maxByOrNull { it.createdTimeMs }?.cameraLabel

    // ---- Số ngày giữ video (0 = giữ vĩnh viễn) ----

    private fun prefs(context: Context) = context.getSharedPreferences("local_video_store", Context.MODE_PRIVATE)

    fun getRetentionDays(context: Context): Int = prefs(context).getInt(KEY_RETENTION_DAYS, 0)

    fun setRetentionDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_RETENTION_DAYS, days).apply()
    }

    private const val KEY_RETENTION_DAYS = "retention_days"
}

/**
 * Dọn video QUÁ HẠN theo tuổi từng video (đọc từ tên file), giống cơ chế trước đây từng dùng
 * cho Drive nhưng giờ chạy thẳng trên bộ nhớ máy — xoá file cục bộ quá hạn để giải phóng dung
 * lượng. retentionDays = 0 -> tắt hẳn, giữ video tới khi người dùng tự xoá.
 */
class LocalRetentionCleaner(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

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
        val days = LocalVideoStore.getRetentionDays(context)
        if (days <= 0) return
        scope.launch {
            val cutoffMs = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
            var deletedCount = 0
            LocalVideoStore.listAll(context).forEach { entry ->
                if (entry.createdTimeMs in 1 until cutoffMs) {
                    if (entry.file.delete()) deletedCount++
                }
            }
            if (deletedCount > 0) Log.d(TAG, "Đã dọn $deletedCount video quá hạn trên máy")
        }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
    }
}
