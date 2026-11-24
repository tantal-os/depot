package app.tantal.depot

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

class ModelAccess(
    val status: String,
    val digest: String? = null,
    val manifest: ByteArray? = null,
    val signature: ByteArray? = null,
    val publisher: String? = null,
    val roles: List<String> = emptyList(),
    val files: List<ParcelFileDescriptor> = emptyList(),
) : Parcelable, AutoCloseable {
    private constructor(parcel: Parcel) : this(
        parcel.readString() ?: "unavailable",
        parcel.readString(),
        parcel.createByteArray(),
        parcel.createByteArray(),
        parcel.readString(),
        parcel.createStringArrayList().orEmpty(),
        parcel.createTypedArrayList(ParcelFileDescriptor.CREATOR).orEmpty(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(status)
        parcel.writeString(digest)
        parcel.writeByteArray(manifest)
        parcel.writeByteArray(signature)
        parcel.writeString(publisher)
        parcel.writeStringList(roles)
        parcel.writeTypedList(files, flags)
    }

    override fun describeContents(): Int = if (files.isEmpty()) 0 else Parcelable.CONTENTS_FILE_DESCRIPTOR

    override fun close() = files.forEach(::closeDescriptor)

    companion object CREATOR : Parcelable.Creator<ModelAccess> {
        override fun createFromParcel(parcel: Parcel) = ModelAccess(parcel)
        override fun newArray(size: Int) = arrayOfNulls<ModelAccess>(size)
    }
}
