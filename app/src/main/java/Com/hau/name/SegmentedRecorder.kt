package Com.hau.name

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SegmentedRecorder"

/**
 * Nhận video track WebRTC (qua [onFrame], gắn làm 1 sink của remote VideoTrack) và ghi liên tục
 * thành các file .mp4 độ dài cố định [segmentDurationMs] (mặc định 30 phút), lưu trong [outputDir].
 *
 * Cách hoạt động:
 * - Mỗi khung hình nhận được được vẽ (qua EglRenderer, dùng GPU) lên Surface đầu vào của một
 *   MediaCodec H.264 encoder — đây là cách chuẩn để "ghi lại" một VideoTrack WebRTC thành file,
 *   không cần tự giải mã YUV bằng tay.
 * - Một thread riêng liên tục rút dữ liệu đã mã hóa (encoder output) và ghi vào MediaMuxer.
 * - Khi đủ [segmentDurationMs], hoặc khi [stop] được gọi (mất kết nối / dừng thủ công), file
 *   hiện tại được CHỐT ĐÚNG CÁCH (signalEndOfInputStream -> rút hết buffer còn lại -> muxer.stop())
 *   nên video đã quay được tới thời điểm đó luôn xem lại được, không bị hỏng file.
 *
 * Toàn bộ xử lý nặng (vẽ GL, encode, mux) chạy trên background thread riêng, không chặn luồng
 * nhận frame của WebRTC.
 */
class SegmentedRecorder(
    private val eglContext: EglBase.Context,
    private val outputDir: File,
    private val segmentDurationMs: Long = 30 * 60 * 1000L,
    /** (file, thời điểm THỰC bắt đầu ghi đoạn này - epoch ms) — dùng thời điểm này (KHÔNG phải
     *  giờ upload lên Drive) để ghép đúng hàng giữa các camera khi xem lại. */
    private val onSegmentSaved: (File, Long) -> Unit = { _, _ -> }
) : VideoSink {

    private val workerThread = HandlerThread("SegmentedRecorderThread").apply { start() }
    private val handler = Handler(workerThread.looper)

    private var eglRenderer: EglRenderer? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var drainThread: Thread? = null
    private val draining = AtomicBoolean(false)

    private var frameWidth = 0
    private var frameHeight = 0
    private var currentFile: File? = null
    private var currentSegmentStartedAtMs = 0L
    private val rotateRunnable = Runnable { rotateSegment() }

    @Volatile private var released = false

    init {
        outputDir.mkdirs()
    }

    /**
     * Gọi từ luồng nhận frame WebRTC (VideoTrack.addSink). Không block lâu.
     *
     * LƯU Ý: KHÔNG cắt đoạn mới khi độ phân giải khung hình thay đổi giữa chừng — WebRTC tự
     * điều chỉnh độ phân giải liên tục theo chất lượng mạng (mạng yếu tự giảm nét, mạng khoẻ
     * tự tăng lại), nên nếu cắt đoạn theo đó sẽ ra hàng loạt file vài giây một. Việc co giãn
     * khung hình vào đúng kích thước bề mặt ghi đã có GPU (EglRenderer) tự lo, không cần can
     * thiệp — cứ vẽ tiếp lên surface hiện tại là đủ.
     */
    override fun onFrame(frame: VideoFrame) {
        if (released) return
        frame.retain()
        handler.post {
            try {
                if (encoder == null) {
                    startNewSegment(frame.rotatedWidth, frame.rotatedHeight)
                }
                eglRenderer?.onFrame(frame)
            } finally {
                frame.release()
            }
        }
    }

    private fun startNewSegment(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
        val startedAtMs = System.currentTimeMillis()
        val file = File(outputDir, fileNameFor(Date(startedAtMs)))
        currentFile = file
        currentSegmentStartedAtMs = startedAtMs

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 20)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec

        val newMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = newMuxer
        muxerStarted = false
        videoTrackIndex = -1

        val renderer = EglRenderer("segment-recorder")
        renderer.init(eglContext, EglBase.CONFIG_RECORDABLE, GlRectDrawer())
        renderer.createEglSurface(inputSurface)
        eglRenderer = renderer

        // Căn lịch cắt đoạn kế tiếp theo ĐÚNG MỐC GIỜ THỰC (vd 15 phút thì luôn là :00/:15/:30/:45),
        // KHÔNG phải "15 phút kể từ lúc bắt đầu ghi" — nhờ vậy các camera ghi độc lập, bắt đầu
        // lệch giờ nhau (tự kết nối lại, thêm camera sau...) vẫn cùng cắt đoạn tại cùng 1 thời
        // điểm tuyệt đối, giúp video các camera khác nhau nhưng cùng khung giờ khớp đúng 1 hàng
        // khi xem lại (xem VideoGalleryActivity / VideoIndexer.buildTimeAlignedRows).
        val delay = segmentDurationMs - (startedAtMs % segmentDurationMs)
        handler.postDelayed(rotateRunnable, delay)
        startDrainThread(codec, newMuxer, file)

        Log.d(TAG, "Bắt đầu đoạn mới: ${file.name} (${width}x${height})")
    }

    private fun startDrainThread(codec: MediaCodec, muxer: MediaMuxer, file: File) {
        draining.set(true)
        val thread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (draining.get()) {
                val outIndex = try {
                    codec.dequeueOutputBuffer(bufferInfo, 10_000)
                } catch (e: IllegalStateException) {
                    break
                }
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            videoTrackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIndex >= 0 -> {
                        val encodedData: ByteBuffer? = codec.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            try {
                                muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            } catch (e: Exception) {
                                Log.w(TAG, "Bỏ qua mẫu lỗi khi ghi ${file.name}: ${e.message}")
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            draining.set(false)
                        }
                    }
                }
            }
        }
        thread.name = "SegmentedRecorder-Drain"
        thread.start()
        drainThread = thread
    }

    /** Chốt đoạn hiện tại (nếu có) và mở đoạn kế tiếp — dùng cho định kỳ 30 phút. */
    private fun rotateSegment() {
        if (released) return
        finishCurrentSegment(startNext = true, nextWidth = frameWidth, nextHeight = frameHeight)
    }

    /**
     * Chốt file đang ghi cho đúng chuẩn mp4 (quan trọng nhất khi mất kết nối giữa chừng):
     * gửi tín hiệu kết thúc luồng cho encoder, chờ thread rút hết buffer còn lại rồi dừng muxer.
     */
    private fun finishCurrentSegment(startNext: Boolean, nextWidth: Int, nextHeight: Int) {
        handler.removeCallbacks(rotateRunnable)
        val codec = encoder ?: return
        val finishedMuxer = muxer
        val finishedFile = currentFile
        val finishedStartedAtMs = currentSegmentStartedAtMs
        val renderer = eglRenderer

        renderer?.releaseEglSurface {
            try {
                codec.signalEndOfInputStream()
            } catch (e: Exception) {
                Log.w(TAG, "signalEndOfInputStream lỗi: ${e.message}")
            }
            drainThread?.join(2000)
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) finishedMuxer?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "muxer.stop() lỗi (file có thể vẫn xem được phần đã ghi): ${e.message}")
            }
            try { finishedMuxer?.release() } catch (_: Exception) {}

            finishedFile?.let {
                Log.d(TAG, "Đã lưu đoạn: ${it.name}")
                onSegmentSaved(it, finishedStartedAtMs)
            }
        }
        renderer?.release()

        encoder = null
        muxer = null
        eglRenderer = null
        currentFile = null

        if (startNext && !released && nextWidth > 0 && nextHeight > 0) {
            handler.post { startNewSegment(nextWidth, nextHeight) }
        }
    }

    /**
     * Dừng ghi hẳn — gọi khi mất kết nối WebRTC hoặc người dùng chủ động dừng.
     * Đoạn đang ghi dở được chốt lại đúng cách nên KHÔNG mất dữ liệu đã quay được
     * tính tới thời điểm dừng.
     */
    fun stop() {
        if (released) return
        released = true
        handler.post {
            finishCurrentSegment(startNext = false, nextWidth = 0, nextHeight = 0)
            workerThread.quitSafely()
        }
    }

    private fun fileNameFor(date: Date): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        return "cam_${fmt.format(date)}.mp4"
    }
}
