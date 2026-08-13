package Com.hau.name

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import Com.hau.name.drive.DriveAccountManager
import Com.hau.name.drive.GalleryRow
import Com.hau.name.drive.VideoEntry
import Com.hau.name.drive.VideoIndexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Danh sách video đã lưu, dạng bảng 4 cột (A)/(B)/(C)/(D) — cột nào hiển thị video của camera
 * đó. Mỗi HÀNG ứng với đúng 1 mốc thời gian thực (làm tròn 15 phút, khớp thời lượng 1 đoạn
 * video) — video của các camera khác nhau quay CÙNG khung giờ đó sẽ nằm CÙNG 1 hàng, đúng cột
 * của camera đó (xem [VideoIndexer.buildTimeAlignedRows]). Camera nào không ghi được đoạn nào
 * trong khung giờ đó thì ô tương ứng để trống (mờ đi). Video lấy từ TẤT CẢ tài khoản Drive đã
 * đăng nhập, gộp lại theo tên file "tênCam (X).mp4".
 *
 * - Nhấn giữ 1 ô để bật chế độ chọn, nhấn thêm các ô KHÁC trong CÙNG 1 HÀNG (tối đa 4, đúng
 *   4 cột A-D) để chọn cùng lúc 2/3/4 video, rồi bấm "Xem cùng lúc" để mở [MultiViewPlayerActivity].
 */
class VideoGalleryActivity : AppCompatActivity() {

    private lateinit var accountManager: DriveAccountManager
    private lateinit var rowsContainer: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var barActions: LinearLayout
    private lateinit var textSelectionCount: TextView

    /** Tất cả video lấy được (dùng để hiện tên camera ở tiêu đề cột). */
    private var allEntries: List<VideoEntry> = emptyList()
    /** Danh sách hàng đã ghép theo đúng mốc thời gian thực (15 phút/hàng) — hàng mới nhất trước. */
    private var rows: List<GalleryRow> = emptyList()

    // Ô đang được chọn cùng hàng — key = "rowIndex", value = tập slotLetter đã chọn trong hàng đó.
    // Chỉ cho phép chọn trong 1 hàng tại 1 thời điểm (chọn hàng khác sẽ tự bỏ chọn hàng cũ).
    private var selectedRow: Int? = null
    private val selectedSlots = LinkedHashSet<Char>()
    private val cellViews = HashMap<String, View>() // "row_slot" -> view overlay chọn

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_gallery)

        accountManager = DriveAccountManager(this)
        rowsContainer = findViewById(R.id.gallery_rows_container)
        progress = findViewById(R.id.progress_loading)
        barActions = findViewById(R.id.bar_selection_actions)
        textSelectionCount = findViewById(R.id.text_selection_count)

        findViewById<Button>(R.id.btn_cancel_selection).setOnClickListener { clearSelection() }
        findViewById<Button>(R.id.btn_view_together).setOnClickListener { openMultiView() }

        loadVideos()
    }

    override fun onResume() {
        super.onResume()
        // Quay lại từ màn xem nhiều video (Back) -> tự động bỏ chọn theo đúng yêu cầu.
        if (::rowsContainer.isInitialized) clearSelection()
    }

    private fun loadVideos() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val entries = try {
                VideoIndexer.fetchAll(this@VideoGalleryActivity, accountManager)
            } catch (e: Exception) {
                Toast.makeText(this@VideoGalleryActivity, "Không tải được danh sách video: ${e.message}", Toast.LENGTH_LONG).show()
                emptyList()
            }
            allEntries = entries
            rows = VideoIndexer.buildTimeAlignedRows(entries)
            progress.visibility = View.GONE
            renderHeaders()
            renderRows()
        }
    }

    private fun renderHeaders() {
        val letters = charArrayOf('A', 'B', 'C', 'D')
        val ids = intArrayOf(R.id.header_col_a, R.id.header_col_b, R.id.header_col_c, R.id.header_col_d)
        for (i in letters.indices) {
            val label = VideoIndexer.latestLabelForSlot(allEntries, letters[i]) ?: "—"
            findViewById<TextView>(ids[i]).text = "(${letters[i]}) $label"
        }
    }

    private fun renderRows() {
        rowsContainer.removeAllViews()
        cellViews.clear()
        if (rows.isEmpty()) {
            rowsContainer.addView(TextView(this).apply {
                text = getString(R.string.gallery_empty)
                setPadding(16, 32, 16, 16)
            })
            return
        }
        rows.forEachIndexed { rowIndex, galleryRow ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for (slot in charArrayOf('A', 'B', 'C', 'D')) {
                val entry = galleryRow.cells[slot]
                rowLayout.addView(buildCell(rowIndex, slot, entry))
            }
            rowsContainer.addView(rowLayout)
        }
    }

    private fun buildCell(row: Int, slot: Char, entry: VideoEntry?): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_video_cell, rowsContainer, false)
        val imageThumb = view.findViewById<ImageView>(R.id.image_thumb)
        val textTime = view.findViewById<TextView>(R.id.text_time)
        val overlay = view.findViewById<View>(R.id.overlay_selected)

        if (entry == null) {
            view.alpha = 0.25f
            return view
        }
        textTime.text = formatTime(entry.createdTimeMs)
        cellViews["${row}_$slot"] = overlay
        loadThumbnail(entry, imageThumb)

        view.setOnClickListener {
            if (selectedRow == row) {
                toggleSlotSelection(row, slot, overlay)
            } else {
                Toast.makeText(this, R.string.gallery_hint, Toast.LENGTH_SHORT).show()
            }
        }
        view.setOnLongClickListener {
            if (selectedRow != row) {
                clearSelection()
                selectedRow = row
            }
            toggleSlotSelection(row, slot, overlay)
            true
        }
        return view
    }

    private fun toggleSlotSelection(row: Int, slot: Char, overlay: View) {
        if (selectedRow != row) return
        if (selectedSlots.contains(slot)) {
            selectedSlots.remove(slot)
            overlay.visibility = View.GONE
        } else {
            selectedSlots.add(slot)
            overlay.visibility = View.VISIBLE
        }
        if (selectedSlots.isEmpty()) selectedRow = null
        updateSelectionBar()
    }

    private fun clearSelection() {
        selectedRow = null
        selectedSlots.clear()
        cellViews.values.forEach { it.visibility = View.GONE }
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        if (selectedSlots.size >= 2) {
            barActions.visibility = View.VISIBLE
            textSelectionCount.text = getString(R.string.gallery_selected_count, selectedSlots.size)
        } else {
            barActions.visibility = if (selectedSlots.isEmpty()) View.GONE else View.VISIBLE
            textSelectionCount.text = if (selectedSlots.size == 1)
                getString(R.string.gallery_selected_need_more) else ""
        }
    }

    private fun openMultiView() {
        val row = selectedRow ?: return
        if (selectedSlots.size < 2) {
            Toast.makeText(this, R.string.gallery_selected_need_more, Toast.LENGTH_SHORT).show()
            return
        }
        val entries = selectedSlots.sorted().mapNotNull { slot -> rows.getOrNull(row)?.cells?.get(slot) }
        MultiViewPlayerActivity.start(this, entries)
    }

    private fun loadThumbnail(entry: VideoEntry, into: ImageView) {
        lifecycleScope.launch {
            val bmp = try {
                withContext(Dispatchers.IO) {
                    val token = Com.hau.name.drive.DriveRest.getAccessToken(this@VideoGalleryActivity, entry.accountEmail)
                    val retriever = MediaMetadataRetriever()
                    // Overload nhận headers -> đọc được vài giây đầu qua HTTP để lấy khung hình đại
                    // diện, KHÔNG cần tải cả file — đủ dùng để hiện ảnh nhỏ trong bảng.
                    retriever.setDataSource(
                        "https://www.googleapis.com/drive/v3/files/${entry.fileId}?alt=media",
                        mapOf("Authorization" to "Bearer $token")
                    )
                    val frame = retriever.getFrameAtTime(0)
                    retriever.release()
                    frame
                }
            } catch (e: Exception) {
                null
            }
            if (bmp != null) into.setImageBitmap(bmp)
        }
    }

    private fun formatTime(ms: Long): String =
        if (ms <= 0) "" else SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(java.util.Date(ms))
}
