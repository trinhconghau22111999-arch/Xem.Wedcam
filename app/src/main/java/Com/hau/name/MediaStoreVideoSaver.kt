package Com.hau.name

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream

private const val TAG = "MediaStoreVideoSaver"

/**
 * Đưa 1 file video đã ghi xong (đã chốt hợp lệ bởi [SegmentedRecorder]) vào bộ sưu tập
 * MediaStore.Video của hệ thống, để nó hiện ra trong app Ảnh/Thư viện (Google Photos, Gallery...)
 * giống như video quay bằng camera thường — thay vì chỉ nằm trong thư mục riêng của app.
 *
 * - Android 10 (API 29) trở lên: dùng MediaStore API mới, ghi thẳng qua ContentResolver,
 *   không cần quyền WRITE_EXTERNAL_STORAGE.
 * - Android 9 trở xuống: copy file vào thư mục công khai Movies/HomeCamera rồi gọi
 *   MediaScannerConnection để hệ thống nhận diện — cần quyền WRITE_EXTERNAL_STORAGE.
 *
 * Sau khi đưa vào thư viện thành công, file gốc trong thư mục riêng của app được xoá để
 * tránh lưu trùng 2 bản.
 */
object MediaStoreVideoSaver {

    private const val ALBUM_NAME = "HomeCamera"

    fun saveToGallery(context: Context, sourceFile: File, cameraLabel: String) {
        if (!sourceFile.exists()) {
            Log.w(TAG, "File không tồn tại, bỏ qua lưu thư viện: ${sourceFile.path}")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, sourceFile, cameraLabel)
            } else {
                saveViaLegacyPublicDir(context, sourceFile, cameraLabel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lưu vào thư viện thất bại, giữ lại file gốc: ${e.message}")
        }
    }

    private fun saveViaMediaStore(context: Context, sourceFile: File, cameraLabel: String) {
        val resolver = context.contentResolver
        val displayName = "${cameraLabel}_${sourceFile.nameWithoutExtension}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$ALBUM_NAME")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: run {
            Log.e(TAG, "Không tạo được entry MediaStore cho $displayName")
            return
        }
        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(sourceFile).use { input -> input.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        sourceFile.delete()
        Log.d(TAG, "Đã lưu vào thư viện: $displayName")
    }

    private fun saveViaLegacyPublicDir(context: Context, sourceFile: File, cameraLabel: String) {
        val moviesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            ALBUM_NAME
        )
        moviesDir.mkdirs()
        val destFile = File(moviesDir, "${cameraLabel}_${sourceFile.name}")
        sourceFile.copyTo(destFile, overwrite = true)
        sourceFile.delete()

        MediaScannerConnection.scanFile(
            context, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null
        )
        Log.d(TAG, "Đã lưu vào thư viện (legacy): ${destFile.absolutePath}")
    }
}
