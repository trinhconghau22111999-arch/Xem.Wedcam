package Com.hau.name.drive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Phạm vi quyền Drive cần xin — cần đủ để đọc dung lượng (about) + tạo/xoá file trong thư mục riêng của app. */
const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"

/** Tỉ lệ dung lượng trống tối thiểu để còn được coi là "còn chỗ" — dưới mức này coi là gần đầy, chuyển tài khoản kế tiếp. */
const val NEAR_FULL_FREE_FRACTION = 0.05f

const val MAX_DRIVE_ACCOUNTS = 20

data class DriveAccount(
    val email: String,
    var order: Int,
    var usedBytes: Long = -1L,
    var totalBytes: Long = -1L, // -1 = chưa kiểm tra; Long.MAX_VALUE = không giới hạn (Workspace)
    var folderId: String? = null
) {
    /** true nếu lần kiểm tra dung lượng gần nhất cho thấy tài khoản đã gần đầy. */
    fun isNearFullCached(): Boolean {
        if (totalBytes <= 0) return false // chưa biết -> coi như còn chỗ, sẽ kiểm tra thật khi upload
        if (totalBytes == Long.MAX_VALUE) return false
        val free = totalBytes - usedBytes
        return free.toFloat() / totalBytes.toFloat() < NEAR_FULL_FREE_FRACTION
    }
}

/**
 * Lưu & quản lý danh sách tài khoản Google Drive dùng để lưu video, tối đa [MAX_DRIVE_ACCOUNTS].
 * Thứ tự (order) quyết định tài khoản nào được ưu tiên lưu video trước (từ trên xuống dưới) —
 * thứ tự này KHÔNG tự đổi; tài khoản nào gần đầy thì tạm bị bỏ qua (xem [DriveUploader]), tự
 * được thử lại khi có chỗ trống trở lại (nhờ video cũ bị dọn dần theo [retentionDays]).
 */
class DriveAccountManager(context: Context) {
    private val prefs = context.getSharedPreferences("drive_accounts", Context.MODE_PRIVATE)

    @Synchronized
    fun listAccounts(): List<DriveAccount> {
        val json = prefs.getString(KEY_LIST, null) ?: return emptyList()
        val arr = JSONArray(json)
        val out = ArrayList<DriveAccount>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += DriveAccount(
                email = o.getString("email"),
                order = o.optInt("order", i),
                usedBytes = o.optLong("usedBytes", -1L),
                totalBytes = o.optLong("totalBytes", -1L),
                folderId = o.optString("folderId", "").ifEmpty { null }
            )
        }
        return out.sortedBy { it.order }
    }

    @Synchronized
    private fun saveAll(accounts: List<DriveAccount>) {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(JSONObject().apply {
                put("email", a.email)
                put("order", a.order)
                put("usedBytes", a.usedBytes)
                put("totalBytes", a.totalBytes)
                put("folderId", a.folderId ?: "")
            })
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** Thêm tài khoản mới vào CUỐI danh sách (order = lớn nhất hiện có + 1). Trả về false nếu đã đủ 20 hoặc đã có sẵn. */
    @Synchronized
    fun addAccount(email: String): Boolean {
        val current = listAccounts()
        if (current.any { it.email == email }) return false
        if (current.size >= MAX_DRIVE_ACCOUNTS) return false
        val nextOrder = (current.maxOfOrNull { it.order } ?: -1) + 1
        val updated = current + DriveAccount(email = email, order = nextOrder)
        saveAll(updated)
        return true
    }

    @Synchronized
    fun removeAccount(email: String) {
        saveAll(listAccounts().filterNot { it.email == email })
    }

    @Synchronized
    fun updateQuota(email: String, usedBytes: Long, totalBytes: Long) {
        val updated = listAccounts().map {
            if (it.email == email) it.copy(usedBytes = usedBytes, totalBytes = totalBytes) else it
        }
        saveAll(updated)
    }

    @Synchronized
    fun setFolderId(email: String, folderId: String) {
        val updated = listAccounts().map { if (it.email == email) it.copy(folderId = folderId) else it }
        saveAll(updated)
    }

    /** Toàn bộ tài khoản, đúng thứ tự ưu tiên lưu video (trên xuống dưới). */
    fun candidatesInOrder(): List<DriveAccount> = listAccounts()

    /** Video cũ hơn số ngày này (tính theo TỪNG VIDEO, không phải theo tài khoản) sẽ tự bị xoá
     *  để giải phóng chỗ trống. 0 = tắt tính năng tự xoá — video giữ vĩnh viễn cho tới khi tài
     *  khoản đầy thật (lúc đó app tự chuyển sang tài khoản kế tiếp, không xoá gì cả). */
    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, 0)
        set(value) { prefs.edit().putInt(KEY_RETENTION_DAYS, value.coerceAtLeast(0)).apply() }

    companion object {
        private const val KEY_LIST = "accounts_json"
        private const val KEY_RETENTION_DAYS = "retention_days"
    }
}
