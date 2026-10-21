package dev.swipeto.sender.media

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** A picked video or photo (content:// from the Photo Picker), read with random access. */
class ContentMediaFile(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val mime: String,
    override val size: Long,
) : MediaFile {
    override fun open(offset: Long): InputStream {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: error("Cannot open $uri")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).apply { channel.position(offset) }
    }
}

/** A plain file; used by tests. */
class LocalFile(private val file: File, override val mime: String) : MediaFile {
    override val size get() = file.length()

    override fun open(offset: Long): InputStream = FileInputStream(file).apply { channel.position(offset) }
}
