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
 *   status         : "waiting" | "connected" | "ended"
 *   consentGivenAt : Long (ms)
 *   offer          : { sdp, type }        — Máy B (host) ghi, Máy A đọc
 *   answer         : { sdp, type }        — Máy A ghi, Máy B đọc
 *   iceCandidatesHost/   {pushId}: {sdpMid, sdpMLineIndex, candidate}  — Máy B ghi
 *   iceCandidatesCtrl/   {pushId}: {sdpMid, sdpMLineIndex, candidate}  — Máy A ghi
 *
 * [isHost] = true  → Máy B (được điều khiển)
 * [isHost] = false → Máy A (máy điều khiển)
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

    private var offerListener: ValueEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var iceListener: ValueEventListener? = null
    private var statusListener: ValueEventListener? = null

    /** Bắt đầu lắng nghe — gọi ngay sau khi PeerConnection đã sẵn sàng. */
    fun start() {
        if (isHost) {
            // Máy B lắng nghe answer từ Máy A
            answerListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                    listener.onAnswerReceived(sdp)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            room.child("answer").addValueEventListener(answerListener!!)
        } else {
            // Máy A lắng nghe offer từ Máy B
            offerListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                    listener.onOfferReceived(sdp)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            room.child("offer").addValueEventListener(offerListener!!)
        }

        // Cả 2 đều lắng nghe ICE candidates của phía kia
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
        room.child(remoteIcePath).addValueEventListener(iceListener!!)

        // Lắng nghe khi phía kia ngắt kết nối
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

    fun sendOffer(sdp: String) {
        room.child("offer").setValue(mapOf("type" to "offer", "sdp" to sdp))
    }

    fun sendAnswer(sdp: String) {
        room.child("answer").setValue(mapOf("type" to "answer", "sdp" to sdp))
    }

    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        room.child(localIcePath).push().setValue(
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
        offerListener?.let { room.child("offer").removeEventListener(it) }
        answerListener?.let { room.child("answer").removeEventListener(it) }
        iceListener?.let { room.child(remoteIcePath).removeEventListener(it) }
        statusListener?.let { room.child("status").removeEventListener(it) }
    }
}
