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
    var isResetting: Boolean = false,
    var lastResetAtMs: Long = 0L,
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
 * Thứ tự (order) quyết định tài khoản nào được ưu tiên lưu video trước (từ trên xuống dưới).
 * Khi 1 tài khoản reset xong, nó được đẩy xuống CUỐI danh sách (order lớn nhất + 1).
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
                isResetting = o.optBoolean("isResetting", false),
                lastResetAtMs = o.optLong("lastResetAtMs", 0L),
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
                put("isResetting", a.isResetting)
                put("lastResetAtMs", a.lastResetAtMs)
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
        val updated = current + DriveAccount(email = email, order = nextOrder, lastResetAtMs = System.currentTimeMillis())
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
            if (it.email == email) it.copy2(usedBytes = usedBytes, totalBytes = totalBytes) else it
        }
        saveAll(updated)
    }

    @Synchronized
    fun setFolderId(email: String, folderId: String) {
        val updated = listAccounts().map { if (it.email == email) it.copy2(folderId = folderId) else it }
        saveAll(updated)
    }

    @Synchronized
    fun markResetting(email: String, resetting: Boolean) {
        val updated = listAccounts().map { if (it.email == email) it.copy2(isResetting = resetting) else it }
        saveAll(updated)
    }

    /** Reset xong: tắt cờ đang-reset, cập nhật thời điểm reset, và đẩy tài khoản này xuống CUỐI danh sách. */
    @Synchronized
    fun completeResetAndMoveToBottom(email: String) {
        val current = listAccounts()
        val maxOrder = current.maxOfOrNull { it.order } ?: -1
        val updated = current.map {
            if (it.email == email) it.copy2(
                isResetting = false, lastResetAtMs = System.currentTimeMillis(),
                order = maxOrder + 1, usedBytes = 0L
            ) else it
        }
        saveAll(updated)
    }

    /** Tài khoản đầu danh sách (theo order), CHƯA đang reset và (theo cache) chưa gần đầy — ứng viên để thử lưu video kế tiếp. */
    fun candidatesInOrder(): List<DriveAccount> =
        listAccounts().filter { !it.isResetting }.sortedBy { it.order }

    /** Số ngày giữa các lần tự động xoá video để giải phóng bộ nhớ. 0 = tắt tính năng tự reset. */
    var resetIntervalDays: Int
        get() = prefs.getInt(KEY_RESET_DAYS, 0)
        set(value) { prefs.edit().putInt(KEY_RESET_DAYS, value.coerceAtLeast(0)).apply() }

    companion object {
        private const val KEY_LIST = "accounts_json"
        private const val KEY_RESET_DAYS = "reset_interval_days"
    }
}

/** Helper copy vì data class DriveAccount có var, dùng copy() chuẩn của Kotlin là đủ — hàm này chỉ để code gọi rõ nghĩa hơn. */
private fun DriveAccount.copy2(
    email: String = this.email,
    order: Int = this.order,
    isResetting: Boolean = this.isResetting,
    lastResetAtMs: Long = this.lastResetAtMs,
    usedBytes: Long = this.usedBytes,
    totalBytes: Long = this.totalBytes,
    folderId: String? = this.folderId
) = DriveAccount(email, order, isResetting, lastResetAtMs, usedBytes, totalBytes, folderId)
