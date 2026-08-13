package Com.hau.name

import android.os.Parcel
import android.os.Parcelable
import Com.hau.name.drive.VideoEntry

/** VideoEntry (trong module drive) không tự Parcelable — bọc tạm để truyền qua Intent giữa các Activity. */
data class VideoEntryParcel(
    val accountEmail: String,
    val fileId: String,
    val fileName: String,
    val cameraLabel: String,
    val slotLetter: Char,
    val createdTimeMs: Long
) : Parcelable {

    fun toEntry() = VideoEntry(accountEmail, fileId, fileName, cameraLabel, slotLetter, createdTimeMs)

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString()?.firstOrNull() ?: '?',
        parcel.readLong()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(accountEmail)
        parcel.writeString(fileId)
        parcel.writeString(fileName)
        parcel.writeString(cameraLabel)
        parcel.writeString(slotLetter.toString())
        parcel.writeLong(createdTimeMs)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<VideoEntryParcel> {
        override fun createFromParcel(parcel: Parcel) = VideoEntryParcel(parcel)
        override fun newArray(size: Int): Array<VideoEntryParcel?> = arrayOfNulls(size)

        fun from(e: VideoEntry) = VideoEntryParcel(e.accountEmail, e.fileId, e.fileName, e.cameraLabel, e.slotLetter, e.createdTimeMs)
    }
}
