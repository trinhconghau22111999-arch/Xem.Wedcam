package Com.hau.name.drive

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "DriveRest"
private const val APP_FOLDER_NAME = "HomeCamera"

data class DriveFileRef(val id: String, val name: String, val createdTimeMs: Long)

/**
 * Gọi thẳng Drive v3 REST API bằng HttpURLConnection có sẵn trong Android — không cần thêm
 * thư viện google-api-client (nhẹ hơn, ít rủi ro xung đột phiên bản khi build).
 */
object DriveRest {

    /** Lấy access token OAuth2 cho tài khoản này với quyền Drive — PHẢI gọi ngoài main thread.
     *  Dùng overload nhận thẳng email (String) — không cần quyền GET_ACCOUNTS, vì token được
     *  cấp dựa trên sự đồng ý (consent) tài khoản này đã cho lúc đăng nhập qua GoogleSignInClient. */
    suspend fun getAccessToken(context: Context, email: String): String = withContext(Dispatchers.IO) {
        GoogleAuthUtil.getToken(context, email, "oauth2:$DRIVE_SCOPE")
    }

    /** Trả về (đã dùng, tổng dung lượng). totalBytes = Long.MAX_VALUE nếu tài khoản không giới hạn (Workspace). */
    suspend fun getQuota(token: String): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val conn = openConn("https://www.googleapis.com/drive/v3/about?fields=storageQuota", token, "GET")
        val body = conn.readBody()
        val quota = JSONObject(body).getJSONObject("storageQuota")
        val used = quota.optString("usage", "0").toLongOrNull() ?: 0L
        val limit = if (quota.has("limit")) quota.optString("limit", "").toLongOrNull() else null
        used to (limit ?: Long.MAX_VALUE)
    }

    /** Tìm hoặc tạo thư mục riêng của app trong Drive của tài khoản này, trả về folder id. */
    suspend fun getOrCreateAppFolder(token: String, cachedFolderId: String?): String = withContext(Dispatchers.IO) {
        if (cachedFolderId != null && folderStillExists(token, cachedFolderId)) return@withContext cachedFolderId

        val q = "mimeType='application/vnd.google-apps.folder' and name='$APP_FOLDER_NAME' and trashed=false"
        val listUrl = "https://www.googleapis.com/drive/v3/files?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&fields=files(id)"
        val found = JSONObject(openConn(listUrl, token, "GET").readBody()).getJSONArray("files")
        if (found.length() > 0) return@withContext found.getJSONObject(0).getString("id")

        val createConn = openConn("https://www.googleapis.com/drive/v3/files", token, "POST", "application/json")
        val meta = JSONObject().apply {
            put("name", APP_FOLDER_NAME)
            put("mimeType", "application/vnd.google-apps.folder")
        }
        createConn.outputStream.use { it.write(meta.toString().toByteArray()) }
        JSONObject(createConn.readBody()).getString("id")
    }

    private fun folderStillExists(token: String, folderId: String): Boolean = try {
        val conn = openConn("https://www.googleapis.com/drive/v3/files/$folderId?fields=id,trashed", token, "GET")
        val obj = JSONObject(conn.readBody())
        !obj.optBoolean("trashed", false)
    } catch (e: Exception) { false }

    /**
     * Upload theo kiểu resumable (bắt buộc cho file video vài trăm MB) — 1) mở phiên upload,
     * 2) gửi toàn bộ file trong 1 lần PUT (đơn giản, đủ dùng cho file cỡ vài trăm MB qua wifi/4G;
     * nếu cần chia nhỏ theo từng chunk có thể mở rộng thêm sau).
     */
    suspend fun uploadResumable(token: String, folderId: String, fileName: String, file: File): String =
        withContext(Dispatchers.IO) {
            val initUrl = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
            val initConn = (initUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("X-Upload-Content-Type", "video/mp4")
                doOutput = true
            }
            val meta = JSONObject().apply {
                put("name", fileName)
                put("parents", org.json.JSONArray().put(folderId))
            }
            initConn.outputStream.use { it.write(meta.toString().toByteArray()) }
            if (initConn.responseCode !in 200..299) {
                throw RuntimeException("Không mở được phiên upload: HTTP ${initConn.responseCode} ${initConn.readErrorBody()}")
            }
            val uploadUrl = initConn.getHeaderField("Location")
                ?: throw RuntimeException("Không nhận được địa chỉ upload (Location header)")
            initConn.disconnect()

            val putConn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                setRequestProperty("Content-Type", "video/mp4")
                setFixedLengthStreamingMode(file.length())
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 120_000
            }
            FileInputStream(file).use { input ->
                putConn.outputStream.use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
            if (putConn.responseCode !in 200..299) {
                throw RuntimeException("Upload thất bại: HTTP ${putConn.responseCode} ${putConn.readErrorBody()}")
            }
            val body = putConn.readBody()
            JSONObject(body).getString("id")
        }

    /** Xoá VĨNH VIỄN toàn bộ file trong 1 thư mục (dùng khi reset bộ nhớ định kỳ). */
    suspend fun deleteAllFilesInFolder(token: String, folderId: String) = withContext(Dispatchers.IO) {
        var pageToken: String? = null
        do {
            val q = "'$folderId' in parents and trashed=false"
            var url = "https://www.googleapis.com/drive/v3/files?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&fields=nextPageToken,files(id)&pageSize=100"
            if (pageToken != null) url += "&pageToken=$pageToken"
            val resp = JSONObject(openConn(url, token, "GET").readBody())
            val files = resp.getJSONArray("files")
            for (i in 0 until files.length()) {
                val id = files.getJSONObject(i).getString("id")
                try {
                    openConn("https://www.googleapis.com/drive/v3/files/$id", token, "DELETE").readBody()
                } catch (e: Exception) {
                    Log.w(TAG, "Không xoá được file $id: ${e.message}")
                }
            }
            pageToken = if (resp.has("nextPageToken")) resp.getString("nextPageToken") else null
        } while (pageToken != null)
    }

    /** Liệt kê toàn bộ video (name, id, createdTime) đang có trong thư mục app của 1 tài khoản. */
    suspend fun listVideos(token: String, folderId: String): List<DriveFileRef> = withContext(Dispatchers.IO) {
        val out = ArrayList<DriveFileRef>()
        var pageToken: String? = null
        do {
            val q = "'$folderId' in parents and trashed=false"
            var url = "https://www.googleapis.com/drive/v3/files?q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                "&fields=nextPageToken,files(id,name,createdTime)&orderBy=createdTime desc&pageSize=200"
            if (pageToken != null) url += "&pageToken=$pageToken"
            val resp = JSONObject(openConn(url, token, "GET").readBody())
            val files = resp.getJSONArray("files")
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                out += DriveFileRef(
                    id = f.getString("id"),
                    name = f.getString("name"),
                    createdTimeMs = parseRfc3339(f.optString("createdTime"))
                )
            }
            pageToken = if (resp.has("nextPageToken")) resp.getString("nextPageToken") else null
        } while (pageToken != null)
        out
    }

    /** Tải file video về [destFile] để phát tạm thời (VideoView chỉ phát được file cục bộ/URL công khai). */
    suspend fun downloadFile(token: String, fileId: String, destFile: File) = withContext(Dispatchers.IO) {
        val conn = openConn("https://www.googleapis.com/drive/v3/files/$fileId?alt=media", token, "GET")
        if (conn.responseCode !in 200..299) {
            throw RuntimeException("Tải video thất bại: HTTP ${conn.responseCode} ${conn.readErrorBody()}")
        }
        conn.inputStream.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun parseRfc3339(s: String): Long = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse(s)?.time ?: 0L
    } catch (e: Exception) { 0L }

    private fun openConn(url: String, token: String, method: String, contentType: String? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $token")
        contentType?.let { conn.setRequestProperty("Content-Type", it) }
        if (method == "POST") conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        return conn
    }

    private fun HttpURLConnection.readBody(): String {
        if (responseCode !in 200..299) {
            throw RuntimeException("HTTP $responseCode: ${readErrorBody()}")
        }
        return inputStream.bufferedReader().use { it.readText() }
    }

    private fun HttpURLConnection.readErrorBody(): String = try {
        errorStream?.bufferedReader()?.use { it.readText() } ?: ""
    } catch (e: Exception) { "" }
}
