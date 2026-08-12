package Com.hau.name.webrtc

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Lớp trao đổi tín hiệu WebRTC (offer / answer / ICE candidates) qua Firebase Realtime Database,
 * cho ĐÚNG 1 cặp kết nối Máy B (camera) <-> 1 Máy A (máy xem) cụ thể, xác định bởi [viewerId].
 *
 * Vì 1 camera giờ phục vụ được NHIỀU máy xem cùng lúc (tối đa 4), mỗi máy xem có 1 "kênh" riêng
 * biệt lập dưới rooms/{code}/viewers/{viewerId}/ — không đụng chạm tới các máy xem khác.
 *
 * Cấu trúc Firebase:
 * rooms/{code}/
 *   consentGivenAt : Long (ms)
 *   viewers/{viewerId}/
 *     present        : true — Máy A ghi khi bắt đầu kết nối; dùng onDisconnect().removeValue()
 *                      để Firebase TỰ xoá nếu máy A mất kết nối đột ngột (rớt mạng/tắt máy) —
 *                      nhờ đó Máy B phát hiện và dọn dẹp đúng máy xem đã rời, không ảnh hưởng
 *                      các máy xem khác.
 *     hostGeneration : Long — Máy B sinh giá trị MỚI mỗi lần (re)bắt đầu đàm phán với RIÊNG máy
 *                      xem này (lần đầu hoặc mỗi lần tự kết nối lại sau khi rớt mạng).
 *     gen/{generation}/
 *       offer          : { sdp, type }        — Máy B ghi, Máy A đọc
 *       answer         : { sdp, type }        — Máy A ghi, Máy B đọc
 *       iceCandidatesHost/{pushId}: {...}      — Máy B ghi
 *       iceCandidatesCtrl/{pushId}: {...}      — Máy A ghi
 *
 * [isHost] = true  → đây là 1 trong các "kênh" phía Máy B, phục vụ riêng máy xem [viewerId]
 * [isHost] = false → Máy A, dùng đúng 1 [viewerId] cố định của chính mình cho camera này
 */
class SignalingClient(
    roomCode: String,
    viewerId: String,
    private val isHost: Boolean,
    private val listener: Listener
) {
    interface Listener {
        fun onOfferReceived(sdp: String)
        fun onAnswerReceived(sdp: String)
        fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        fun onRemoteDisconnected()
    }

    private val viewerRef: DatabaseReference =
        FirebaseDatabase.getInstance().reference
            .child("rooms").child(roomCode).child("viewers").child(viewerId)

    private val localIcePath get() = if (isHost) "iceCandidatesHost" else "iceCandidatesCtrl"
    private val remoteIcePath get() = if (isHost) "iceCandidatesCtrl" else "iceCandidatesHost"

    /** "Thế hệ" đàm phán hiện tại của riêng kênh này — quyết định bởi Máy B. */
    private var generation: Long = -1
    /** Node Firebase tương ứng với [generation] hiện tại — nơi thực sự đọc/ghi offer/answer/ICE. */
    private var genRef: DatabaseReference? = null

    private var hostGenerationListener: ValueEventListener? = null
    private var offerListener: ValueEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var iceListener: ValueEventListener? = null
    private var presentListener: ValueEventListener? = null

    /** Bắt đầu lắng nghe — gọi ngay sau khi PeerConnection đã sẵn sàng. */
    fun start() {
        if (isHost) {
            // Máy B: mở 1 "thế hệ" đàm phán mới cho riêng máy xem này, dọn dữ liệu cũ.
            generation = System.currentTimeMillis()
            genRef = viewerRef.child("gen").child(generation.toString())
            viewerRef.child("gen").removeValue()
            viewerRef.child("hostGeneration").setValue(generation)

            answerListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                    listener.onAnswerReceived(sdp)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            genRef!!.child("answer").addValueEventListener(answerListener!!)

            iceListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.children.forEach { child ->
                        val sdpMid = child.child("sdpMid").getValue(String::class.java) ?: return@forEach
                        val sdpMLineIndex = child.child("sdpMLineIndex").getValue(Int::class.java) ?: return@forEach
                        val candidate = child.child("candidate").getValue(String::class.java) ?: return@forEach
                        listener.onIceCandidateReceived(sdpMid, sdpMLineIndex, candidate)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            genRef!!.child(remoteIcePath).addValueEventListener(iceListener!!)

            // Máy B phát hiện máy xem này rời đi (Firebase tự xoá "present" khi rớt kết nối,
            // hoặc máy xem tự xoá khi người dùng chủ động ngắt).
            presentListener = object : ValueEventListener {
                var sawPresent = false
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        sawPresent = true
                    } else if (sawPresent) {
                        listener.onRemoteDisconnected()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            viewerRef.child("present").addValueEventListener(presentListener!!)
        } else {
            // Máy A: đánh dấu có mặt, tự dọn nếu mất kết nối Firebase đột ngột
            viewerRef.child("present").setValue(true)
            viewerRef.child("present").onDisconnect().removeValue()

            // Theo dõi hostGeneration — mỗi khi Máy B mở phiên đàm phán mới (giá trị đổi),
            // tự gỡ listener của thế hệ cũ và chuyển hẳn sang nghe offer/ICE của thế hệ mới.
            hostGenerationListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val gen = snapshot.getValue(Long::class.java) ?: return
                    if (gen == generation) return
                    generation = gen
                    resubscribeToGeneration(gen)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            viewerRef.child("hostGeneration").addValueEventListener(hostGenerationListener!!)
        }
    }

    /** (Chỉ Máy A) Chuyển sang nghe offer + ICE candidate của Máy B trong thế hệ đàm phán mới. */
    private fun resubscribeToGeneration(gen: Long) {
        offerListener?.let { genRef?.child("offer")?.removeEventListener(it) }
        iceListener?.let { genRef?.child(remoteIcePath)?.removeEventListener(it) }

        genRef = viewerRef.child("gen").child(gen.toString())

        offerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                listener.onOfferReceived(sdp)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        genRef!!.child("offer").addValueEventListener(offerListener!!)

        iceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.children.forEach { child ->
                    val sdpMid = child.child("sdpMid").getValue(String::class.java) ?: return@forEach
                    val sdpMLineIndex = child.child("sdpMLineIndex").getValue(Int::class.java) ?: return@forEach
                    val candidate = child.child("candidate").getValue(String::class.java) ?: return@forEach
                    listener.onIceCandidateReceived(sdpMid, sdpMLineIndex, candidate)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        genRef!!.child(remoteIcePath).addValueEventListener(iceListener!!)
    }

    fun sendOffer(sdp: String) {
        genRef?.child("offer")?.setValue(mapOf("type" to "offer", "sdp" to sdp))
    }

    fun sendAnswer(sdp: String) {
        genRef?.child("answer")?.setValue(mapOf("type" to "answer", "sdp" to sdp))
    }

    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        genRef?.child(localIcePath)?.push()?.setValue(
            mapOf("sdpMid" to sdpMid, "sdpMLineIndex" to sdpMLineIndex, "candidate" to candidate)
        )
    }

    fun markConnected() {
        // Giữ nguyên "present" — không cần đổi gì thêm, WebRTC tự báo trạng thái qua onConnected.
    }

    /** (Chỉ Máy A) Chủ động rời khỏi camera này — Máy B sẽ phát hiện ngay lập tức. */
    fun markEnded() {
        if (!isHost) {
            viewerRef.child("present").onDisconnect().cancel()
            viewerRef.removeValue()
        }
    }

    /** Dọn toàn bộ listener khi phiên kết thúc để tránh rò rỉ bộ nhớ. */
    fun release() {
        hostGenerationListener?.let { viewerRef.child("hostGeneration").removeEventListener(it) }
        offerListener?.let { genRef?.child("offer")?.removeEventListener(it) }
        answerListener?.let { genRef?.child("answer")?.removeEventListener(it) }
        iceListener?.let { genRef?.child(remoteIcePath)?.removeEventListener(it) }
        presentListener?.let { viewerRef.child("present").removeEventListener(it) }
    }
}
