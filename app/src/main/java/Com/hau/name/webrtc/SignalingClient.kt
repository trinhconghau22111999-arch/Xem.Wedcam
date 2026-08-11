package Com.hau.name.webrtc

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Lớp trao đổi tín hiệu WebRTC (offer / answer / ICE candidates) qua Firebase Realtime Database.
 *
 * Cấu trúc Firebase:
 * rooms/{code}/
 *   status          : "waiting" | "connected" | "ended"
 *   consentGivenAt  : Long (ms)
 *   hostGeneration  : Long — Máy B (host) sinh 1 giá trị MỚI mỗi lần bắt đầu 1 phiên đàm phán
 *                     (lần đầu hoặc mỗi lần tự kết nối lại). Máy A theo dõi giá trị này để biết
 *                     lúc nào cần bỏ toàn bộ offer/ICE candidate CŨ và chuyển sang nghe đúng dữ
 *                     liệu của phiên MỚI — tránh việc đọc nhầm offer/ICE của lần kết nối trước
 *                     (nguyên nhân khiến kết nối lại bằng mã cũ hay bị treo/thất bại).
 *   gen/{generation}/
 *     offer         : { sdp, type }        — Máy B ghi, Máy A đọc
 *     answer        : { sdp, type }        — Máy A ghi, Máy B đọc
 *     iceCandidatesHost/{pushId}: {...}     — Máy B ghi
 *     iceCandidatesCtrl/{pushId}: {...}     — Máy A ghi
 *
 * [isHost] = true  → Máy B (camera)
 * [isHost] = false → Máy A (máy xem)
 */
class SignalingClient(
    private val roomCode: String,
    private val isHost: Boolean,
    private val listener: Listener
) {
    interface Listener {
        fun onOfferReceived(sdp: String)
        fun onAnswerReceived(sdp: String)
        fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        fun onRemoteDisconnected()
    }

    private val room: DatabaseReference =
        FirebaseDatabase.getInstance().reference.child("rooms").child(roomCode)

    private val localIcePath get() = if (isHost) "iceCandidatesHost" else "iceCandidatesCtrl"
    private val remoteIcePath get() = if (isHost) "iceCandidatesCtrl" else "iceCandidatesHost"

    /** "Thế hệ" đàm phán hiện tại — quyết định bởi Máy B. */
    private var generation: Long = -1
    /** Node Firebase tương ứng với [generation] hiện tại — nơi thực sự đọc/ghi offer/answer/ICE. */
    private var genRef: DatabaseReference? = null

    private var hostGenerationListener: ValueEventListener? = null
    private var offerListener: ValueEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var iceListener: ValueEventListener? = null
    private var statusListener: ValueEventListener? = null

    /** Bắt đầu lắng nghe — gọi ngay sau khi PeerConnection đã sẵn sàng. */
    fun start() {
        if (isHost) {
            // Máy B: mở 1 "thế hệ" đàm phán mới, dọn sạch dữ liệu đàm phán cũ, rồi công bố
            // hostGeneration mới để Máy A (nếu đang lắng nghe) tự chuyển sang nghe đúng chỗ.
            generation = System.currentTimeMillis()
            genRef = room.child("gen").child(generation.toString())
            room.child("gen").removeValue()
            room.child("hostGeneration").setValue(generation)

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
        } else {
            // Máy A: theo dõi hostGeneration — mỗi khi Máy B mở phiên đàm phán mới (giá trị đổi),
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
            room.child("hostGeneration").addValueEventListener(hostGenerationListener!!)
        }

        // Lắng nghe khi phía kia chủ động kết thúc hẳn (không phải rớt mạng tạm thời)
        statusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.getValue(String::class.java) == "ended") {
                    listener.onRemoteDisconnected()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        room.child("status").addValueEventListener(statusListener!!)
    }

    /** (Chỉ Máy A) Chuyển sang nghe offer + ICE candidate của Máy B trong thế hệ đàm phán mới. */
    private fun resubscribeToGeneration(gen: Long) {
        offerListener?.let { genRef?.child("offer")?.removeEventListener(it) }
        iceListener?.let { genRef?.child(remoteIcePath)?.removeEventListener(it) }

        genRef = room.child("gen").child(gen.toString())

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
        room.child("status").setValue("connected")
    }

    fun markEnded() {
        room.child("status").setValue("ended")
    }

    /** Dọn toàn bộ listener khi phiên kết thúc để tránh rò rỉ bộ nhớ. */
    fun release() {
        hostGenerationListener?.let { room.child("hostGeneration").removeEventListener(it) }
        offerListener?.let { genRef?.child("offer")?.removeEventListener(it) }
        answerListener?.let { genRef?.child("answer")?.removeEventListener(it) }
        iceListener?.let { genRef?.child(remoteIcePath)?.removeEventListener(it) }
        statusListener?.let { room.child("status").removeEventListener(it) }
    }
}
