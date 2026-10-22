package dev.swipeto.sender.apps

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import dev.swipeto.protocol.MediaKind
import dev.swipeto.sender.PickedItem
import kotlin.math.abs

/** A video in the phone's media library, as the resolver compares it. */
data class LibraryVideo(val id: Long, val displayName: String, val title: String?, val bucket: String?, val durationMs: Long?, val mime: String?, val size: Long)

/**
 * Finds the file another app is playing from what its media session says. The system video player
 * (where Gallery sends a video played full screen) gives the title, the album (= folder) and the
 * duration, which on the A15 pointed at exactly one file. Apps that give no title (Gallery's own
 * inline player) can't be resolved here.
 */
class LocalMediaResolver(private val context: Context) {
    val permission: String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    val hasPermission: Boolean
        get() = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** The file [session] is playing, or null if it can't be told apart (or nothing matches). */
    fun resolve(session: SessionSnapshot): PickedItem? {
        val title = session.title?.takeIf { it.isNotBlank() } ?: return null
        if (!hasPermission) return null
        val candidates = query(title)
        val match = chooseMatch(candidates, title, session.metadata["ALBUM"], session.durationMs) ?: return null
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, match.id)
        return PickedItem(uri, title.take(200), match.mime?.takeIf { it.startsWith("video/") } ?: "video/mp4", match.size, match.durationMs ?: session.durationMs, MediaKind.VIDEO)
    }

    private fun query(title: String): List<LibraryVideo> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
        )
        val selection = "${MediaStore.Video.Media.TITLE} = ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf(title, "${title.replace("%", "\\%").replace("_", "\\_")}.%")
        val found = mutableListOf<LibraryVideo>()
        context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                found += LibraryVideo(
                    id = c.getLong(0),
                    displayName = c.getString(1) ?: "",
                    title = c.getString(2),
                    bucket = c.getString(3),
                    durationMs = if (c.isNull(4)) null else c.getLong(4),
                    mime = c.getString(5),
                    size = c.getLong(6),
                )
            }
        }
        return found
    }

    companion object {
        /** Durations from the session and the library agree within this. */
        const val DURATION_SLACK_MS = 1_500L

        /**
         * The one candidate that fits [title], [album] (the folder) and [durationMs]; null when none
         * does or several still do (better to ask than to send the wrong video).
         */
        fun chooseMatch(candidates: List<LibraryVideo>, title: String, album: String?, durationMs: Long?): LibraryVideo? {
            var fits = candidates.filter { it.title == title || it.displayName.substringBeforeLast('.') == title }
            if (durationMs != null) fits = fits.filter { it.durationMs == null || abs(it.durationMs - durationMs) <= DURATION_SLACK_MS }
            if (fits.size > 1 && !album.isNullOrBlank()) fits = fits.filter { it.bucket == album }.ifEmpty { fits }
            return fits.singleOrNull()
        }
    }
}
