package Com.hau.name

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import Com.hau.name.storage.VideoEntry
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Xem cùng lúc 2-4 video đã chọn từ [VideoGalleryActivity] (cùng 1 hàng, tức cùng 1 mốc
 * thời gian ghi). Video đã nằm sẵn trên chính máy này (không có Drive, không có proxy hay
 * server nào) nên VideoView phát THẲNG từ file cục bộ — tua tự nhiên, không cần chờ tải.
 *
 * - Chạm 1 video đang xem cùng lúc -> video đó phóng to ra GIỮA màn hình, CÁC VIDEO KHÁC
 *   VẪN TIẾP TỤC CHẠY (không dừng) ở lưới nhỏ phía dưới lớp phóng to.
 * - Chạm nút góc trái trên (khi đang phóng to) -> thu lại về trạng thái xem lưới nhiều video.
 * - Nhấn nút Back của hệ thống -> thoát hẳn màn này, quay về màn danh sách video ở trạng thái
 *   CHỌN (tự động bỏ chọn) — xử lý bằng cách chỉ finish(), màn Gallery tự bỏ chọn khi mở lại.
 */
class MultiViewPlayerActivity : AppCompatActivity() {

    private data class Pane(
        val entry: VideoEntry,
        val root: View,
        val videoView: android.widget.VideoView,
        val progress: ProgressBar
    )

    private lateinit var gridContainer: LinearLayout
    private lateinit var enlargedContainer: FrameLayout
    private lateinit var btnCollapse: ImageButton
    private val panes = ArrayList<Pane>()
    private var enlargedIndex: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multi_view_player)

        gridContainer = findViewById(R.id.grid_container)
        enlargedContainer = findViewById(R.id.enlarged_container)
        btnCollapse = findViewById(R.id.btn_collapse_enlarged)
        btnCollapse.setOnClickListener { collapseToGrid() }

        val entries = intent.getParcelableArrayListExtra<VideoEntryParcel>(EXTRA_ENTRIES)
            ?.map { it.toEntry() } ?: emptyList()
        if (entries.size < 2) { finish(); return }

        buildGrid(entries)
        panes.forEachIndexed { i, _ -> startStreaming(i) }
    }

    private fun buildGrid(entries: List<VideoEntry>) {
        gridContainer.removeAllViews()
        // 2 video -> 1 hàng x 2 cột; 3-4 video -> 2 hàng x 2 cột (ô cuối trống nếu chỉ có 3).
        val rows = if (entries.size <= 2) 1 else 2
        var idx = 0
        for (r in 0 until rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0
                ).apply { weight = 1f }
            }
            val colsInRow = if (entries.size <= 2) entries.size else 2
            for (c in 0 until colsInRow) {
                if (idx >= entries.size) break
                val entry = entries[idx]
                val pane = inflatePane(entry)
                pane.root.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                pane.root.setOnClickListener { enlargePane(panes.indexOf(pane)) }
                rowLayout.addView(pane.root)
                panes += pane
                idx++
            }
            gridContainer.addView(rowLayout)
        }
    }

    private fun inflatePane(entry: VideoEntry): Pane {
        val root = LayoutInflater.from(this).inflate(R.layout.item_player_pane, gridContainer, false)
        val videoView = root.findViewById<android.widget.VideoView>(R.id.video_view)
        val progress = root.findViewById<ProgressBar>(R.id.progress_pane)
        val label = root.findViewById<TextView>(R.id.text_pane_label)
        label.text = "${entry.cameraLabel} (${entry.slotLetter}) · ${formatTime(entry.createdTimeMs)}"
        return Pane(entry, root, videoView, progress)
    }

    private fun startStreaming(index: Int) {
        val pane = panes.getOrNull(index) ?: return
        try {
            pane.videoView.setVideoURI(Uri.fromFile(pane.entry.file))
            pane.videoView.setOnPreparedListener {
                it.isLooping = true
                pane.progress.visibility = View.GONE
                pane.videoView.start()
            }
            pane.videoView.setOnErrorListener { _, what, extra ->
                pane.progress.visibility = View.GONE
                Toast.makeText(this, "Lỗi phát video: mã $what/$extra", Toast.LENGTH_SHORT).show()
                true
            }
            pane.videoView.start()
        } catch (e: Exception) {
            pane.progress.visibility = View.GONE
            Toast.makeText(this, "Không phát được video: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Phóng to ô [index] ra giữa màn hình — KHÔNG dừng các video khác, chúng vẫn chạy phía dưới lớp phóng to. */
    private fun enlargePane(index: Int) {
        val pane = panes.getOrNull(index) ?: return
        // Chuyển root view của pane này từ lưới sang lớp phóng to (không tạo VideoView mới ->
        // không mất trạng thái đang phát, không giật hình).
        (pane.root.parent as? android.view.ViewGroup)?.removeView(pane.root)
        pane.root.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        enlargedContainer.addView(pane.root)
        enlargedContainer.visibility = View.VISIBLE
        btnCollapse.visibility = View.VISIBLE
        btnCollapse.bringToFront()
        enlargedIndex = index
    }

    /** Thu ô đang phóng to về lại đúng vị trí trong lưới — video vẫn đang chạy liên tục suốt lúc phóng to. */
    private fun collapseToGrid() {
        val index = enlargedIndex ?: return
        val pane = panes.getOrNull(index) ?: return
        (pane.root.parent as? android.view.ViewGroup)?.removeView(pane.root)
        enlargedContainer.visibility = View.GONE
        btnCollapse.visibility = View.GONE
        enlargedIndex = null
        rebuildGridKeepingPanes()
    }

    /** Đặt lại đúng vị trí các pane (đã tồn tại, đang phát) vào lưới sau khi 1 ô vừa được thu nhỏ lại. */
    private fun rebuildGridKeepingPanes() {
        gridContainer.removeAllViews()
        val rows = if (panes.size <= 2) 1 else 2
        var idx = 0
        for (r in 0 until rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
            }
            val colsInRow = if (panes.size <= 2) panes.size else 2
            for (c in 0 until colsInRow) {
                if (idx >= panes.size) break
                val pane = panes[idx]
                (pane.root.parent as? android.view.ViewGroup)?.removeView(pane.root)
                pane.root.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                rowLayout.addView(pane.root)
                idx++
            }
            gridContainer.addView(rowLayout)
        }
    }

    override fun onBackPressed() {
        // Theo đúng yêu cầu: Back luôn thoát hẳn về màn danh sách video (không chỉ thu nhỏ ô
        // đang phóng to) — màn Gallery tự bỏ chọn khi quay lại (xem VideoGalleryActivity.onResume).
        super.onBackPressed()
    }

    override fun onDestroy() {
        panes.forEach { it.videoView.stopPlayback() }
        super.onDestroy()
    }

    private fun formatTime(ms: Long): String =
        if (ms <= 0) "" else SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(java.util.Date(ms))

    companion object {
        private const val EXTRA_ENTRIES = "entries"

        fun start(context: Context, entries: List<VideoEntry>) {
            val intent = Intent(context, MultiViewPlayerActivity::class.java)
            intent.putParcelableArrayListExtra(EXTRA_ENTRIES, ArrayList(entries.map { VideoEntryParcel.from(it) }))
            context.startActivity(intent)
        }
    }
}

