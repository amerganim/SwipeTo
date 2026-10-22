package dev.swipeto.sender.apps

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import dev.swipeto.protocol.MediaKind
import dev.swipeto.sender.PickedItem

/**
 * The newest photos and videos on the phone, newest first like Gallery shows them. Offered on the
 * swipe bubble when Gallery is open, since Gallery does not say which one is on screen.
 */
class RecentMedia(private val context: Context) {
    val permissions: Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val hasPermission: Boolean
        get() = permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun newest(limit: Int = 15): List<PickedItem> = query(limit).map { it.second }

    /**
     * The photo or video with MediaStore [id] and its neighbours in Gallery's order (newest taken
     * first), so left and right on the screen page through them; null if it isn't in the library.
     */
    fun around(id: Long, radius: Int = AROUND): Pair<List<PickedItem>, Int>? {
        val all = query(SCAN_LIMIT)
        val index = all.indexOfFirst { it.first == id }.takeIf { it >= 0 } ?: return null
        return window(all.map { it.second }, index, radius)
    }

    private fun query(limit: Int): List<Pair<Long, PickedItem>> {
        if (!hasPermission) return emptyList()
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Video.VideoColumns.DURATION,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val items = mutableListOf<Pair<Long, PickedItem>>()
        // Gallery's order: newest taken first.
        val order = "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        context.contentResolver.query(files, projection, selection, null, order)?.use { c ->
            while (c.moveToNext() && items.size < limit) {
                val id = c.getLong(0)
                val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val uri = ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val name = (c.getString(2) ?: "").substringBeforeLast('.').ifBlank { if (video) "Video" else "Photo" }
                val mime = c.getString(3) ?: if (video) "video/mp4" else "image/jpeg"
                items += id to PickedItem(uri, name.take(200), mime, c.getLong(4), if (video && !c.isNull(5)) c.getLong(5) else null, if (video) MediaKind.VIDEO else MediaKind.IMAGE)
            }
        }
        return items
    }

    companion object {
        /** Neighbours kept on each side of a shared photo, for paging. */
        const val AROUND = 25
        private const val SCAN_LIMIT = 2_000

        /** [items] within [radius] of [index], and where [index] landed in that window. */
        fun <T> window(items: List<T>, index: Int, radius: Int): Pair<List<T>, Int> {
            val from = (index - radius).coerceAtLeast(0)
            val to = (index + radius + 1).coerceAtMost(items.size)
            return items.subList(from, to) to index - from
        }
    }

    fun thumbnail(item: PickedItem, px: Int): Bitmap? =
        if (Build.VERSION.SDK_INT < 29) null else runCatching { context.contentResolver.loadThumbnail(item.uri, Size(px, px), null) }.getOrNull()
}
