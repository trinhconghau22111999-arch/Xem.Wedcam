package Com.hau.name.drive

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

private const val TAG = "DriveStreamProxy"

/**
 * VideoView/MediaPlayer chỉ phát được URL cục bộ hoặc URL HTTP công khai — không tự gắn được
 * header "Authorization: Bearer <token>" cần thiết để phát trực tiếp link riêng tư của Drive.
 * Vì vậy trước đây phải TẢI HẾT file về máy rồi mới phát được (chờ lâu, không tua được khi
 * đang tải, và tốn dung lượng máy tạm thời).
 *
 * Lớp này dựng 1 máy chủ HTTP nhỏ chạy NGAY TRÊN MÁY, chỉ lắng nghe ở địa chỉ loopback
 * (127.0.0.1 — không ai ngoài chính máy này gọi vào được). VideoView phát 1 URL dạng
 * "http://127.0.0.1:<port>/stream?...", proxy này nhận request đó (kể cả header Range khi
 * VideoView tua), gắn thêm token xác thực rồi CHUYỂN TIẾP sang Drive, rồi truyền thẳng dữ liệu
 * Drive trả về ngược lại cho VideoView — video được STREAM thật, tua được, không cần tải hết
 * trước, và không có video nào được lưu xuống bộ nhớ máy trong quá trình này.
 */
object DriveStreamProxy {
    private var serverSocket: ServerSocket? = null
    @Volatile private var port: Int = -1
    private var appContext: Context? = null

    /** Đảm bảo máy chủ đang chạy, trả về cổng (port) đang lắng nghe — gọi bao nhiêu lần cũng an toàn. */
    @Synchronized
    fun ensureStarted(context: Context): Int {
        appContext?.let { if (port > 0) return port }
        appContext = context.applicationContext
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        Thread({ acceptLoop(socket) }, "DriveStreamProxy-accept").apply { isDaemon = true; start() }
        return port
    }

    /** URL để đưa thẳng vào VideoView.setVideoURI(...) — phát video [fileId] thuộc tài khoản [email]. */
    fun urlFor(context: Context, fileId: String, email: String): String {
        val p = ensureStarted(context)
        val encId = URLEncoder.encode(fileId, "UTF-8")
        val encEmail = URLEncoder.encode(email, "UTF-8")
        return "http://127.0.0.1:$p/stream?id=$encId&email=$encEmail"
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (e: Exception) { break }
            Thread({ handleClient(client) }, "DriveStreamProxy-client").apply { isDaemon = true; start() }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]

            var rangeHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.equals("Range", ignoreCase = true)) rangeHeader = value
                }
            }

            val query = path.substringAfter('?', "")
            val params = query.split('&').mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2) URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8") else null
            }.toMap()
            val fileId = params["id"]
            val email = params["email"]
            if (fileId == null || email == null) {
                writeError(client.getOutputStream(), 400, "Thiếu tham số")
                return
            }

            proxyToDrive(client.getOutputStream(), fileId, email, rangeHeader)
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi phục vụ client stream: ${e.message}")
        } finally {
            try { client.close() } catch (e: Exception) { /* bỏ qua */ }
        }
    }

    private fun proxyToDrive(out: OutputStream, fileId: String, email: String, rangeHeader: String?) {
        val context = appContext ?: return
        val token = try {
            runBlocking { DriveRest.getAccessToken(context, email) }
        } catch (e: Exception) {
            writeError(out, 502, "Không lấy được quyền truy cập Drive: ${e.message}")
            return
        }

        val conn = (URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .openConnection() as HttpURLConnection)
        conn.setRequestProperty("Authorization", "Bearer $token")
        rangeHeader?.let { conn.setRequestProperty("Range", it) }
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                writeError(out, 502, "Drive trả lỗi HTTP $code")
                return
            }
            val statusLine = if (code == 206) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
            val headers = StringBuilder()
            headers.append(statusLine).append("\r\n")
            headers.append("Content-Type: video/mp4\r\n")
            headers.append("Accept-Ranges: bytes\r\n")
            conn.getHeaderField("Content-Length")?.let { headers.append("Content-Length: $it\r\n") }
            conn.getHeaderField("Content-Range")?.let { headers.append("Content-Range: $it\r\n") }
            headers.append("Connection: close\r\n\r\n")
            out.write(headers.toString().toByteArray())

            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi chuyển tiếp dữ liệu từ Drive: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun writeError(out: OutputStream, code: Int, message: String) {
        try {
            val body = message.toByteArray()
            val headers = "HTTP/1.1 $code Error\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            out.write(headers.toByteArray())
            out.write(body)
            out.flush()
        } catch (e: Exception) { /* bỏ qua */ }
    }
}
