package dev.swipeto.sender.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One app's media session as Android exposes it, every field included (for the debug screen and the log). */
data class SessionSnapshot(
    val packageName: String,
    val appLabel: String,
    val state: String,
    val playing: Boolean,
    /** Position when [positionAtElapsedMs] was taken; see [positionNow]. */
    val positionMs: Long?,
    val positionAtElapsedMs: Long,
    val speed: Float,
    val durationMs: Long?,
    val title: String?,
    val mediaId: String?,
    /** Every metadata key with a printable value (bitmaps as their size). */
    val metadata: Map<String, String>,
    /** Session and playback-state extras, controller details (queue, activity, actions). */
    val details: Map<String, String>,
) {
    /** Where playback is now, extrapolated from the last update at [speed]. */
    fun positionNow(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long? =
        positionMs?.let { estimatePosition(it, positionAtElapsedMs, speed, playing, nowElapsedMs, durationMs) }
}

/** Playback position now, from Android's (position, update time, speed). Never past [durationMs]. */
fun estimatePosition(positionMs: Long, updatedAtMs: Long, speed: Float, playing: Boolean, nowMs: Long, durationMs: Long?): Long {
    val moved = if (playing) ((nowMs - updatedAtMs).coerceAtLeast(0) * speed).toLong() else 0L
    val estimate = (positionMs + moved).coerceAtLeast(0)
    return durationMs?.takeIf { it > 0 }?.let { estimate.coerceAtMost(it) } ?: estimate
}

/**
 * What other apps are playing (YouTube, Gallery's video player, Spotify...), from Android's media
 * sessions (docs: PLAN.md, Phase 5B). Needs the user to grant Notification access to
 * [MediaNotificationListener]; without it [sessions] stays empty and [hasAccess] is false.
 *
 * Reads only media sessions: package, title, position, play state and what the app puts in its
 * session metadata. Never notifications, never the screen.
 */
class ActiveMediaWatcher(private val context: Context, private val log: (String) -> Unit = {}) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaNotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val controllers = mutableListOf<Pair<MediaController, MediaController.Callback>>()
    private val lastLogged = mutableMapOf<String, String>()
    private var watching = false

    private val _sessions = MutableStateFlow<List<SessionSnapshot>>(emptyList())
    val sessions: StateFlow<List<SessionSnapshot>> = _sessions.asStateFlow()

    /** The session of another app that is playing right now (the most recently changed), or null. */
    private val _playing = MutableStateFlow<SessionSnapshot?>(null)
    val playing: StateFlow<SessionSnapshot?> = _playing.asStateFlow()

    fun session(packageName: String): SessionSnapshot? = _sessions.value.firstOrNull { it.packageName == packageName }

    fun controller(packageName: String): MediaController? = controllers.firstOrNull { it.first.packageName == packageName }?.first

    val hasAccess: Boolean
        get() = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list.orEmpty()) }

    /** Starts watching if access is granted; safe to call again (after the user granted it). */
    fun start() {
        if (watching || !hasAccess) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, main)
            attach(manager.getActiveSessions(component))
            watching = true
            log("media sessions: watching")
        } catch (e: SecurityException) {
            log("media sessions: no access ($e)")
        }
    }

    fun stop() {
        if (!watching) return
        manager.removeOnActiveSessionsChangedListener(sessionsChanged)
        detachAll()
        watching = false
        _sessions.value = emptyList()
        _playing.value = null
    }

    private fun attach(list: List<MediaController>) {
        detachAll()
        for (controller in list) {
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onSessionDestroyed() = refresh()
                override fun onExtrasChanged(extras: Bundle?) = refresh()
            }
            controller.registerCallback(callback, main)
            controllers += controller to callback
        }
        refresh()
    }

    private fun detachAll() {
        controllers.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        controllers.clear()
    }

    private fun refresh() {
        val snapshots = controllers.map { (controller, _) -> snapshot(controller) }
            .filter { it.packageName != context.packageName }
        _sessions.value = snapshots
        // Android lists sessions most recently active first.
        _playing.value = snapshots.firstOrNull { it.playing }
        for (s in snapshots) {
            // Log what an app exposes whenever it changes (not every position tick), for reading over adb.
            val key = "${s.state} ${s.title} ${s.mediaId} ${s.metadata} ${s.details}"
            if (lastLogged[s.packageName] != key) {
                lastLogged[s.packageName] = key
                log("media session ${s.packageName} (${s.appLabel}): ${s.state} at ${s.positionNow()} ms of ${s.durationMs}, " +
                    "title=${s.title}, mediaId=${s.mediaId}, metadata=${s.metadata}, details=${s.details}")
            }
        }
    }

    private fun snapshot(controller: MediaController): SessionSnapshot {
        val playback = controller.playbackState
        val metadata = controller.metadata
        val fields = linkedMapOf<String, String>()
        metadata?.keySet()?.sorted()?.forEach { key -> printable(metadata, key)?.let { fields[key.removePrefix("android.media.metadata.")] = it } }
        val details = linkedMapOf<String, String>()
        controller.extras?.let { putBundle(details, "sessionExtras", it) }
        playback?.extras?.let { putBundle(details, "stateExtras", it) }
        playback?.let { details["actions"] = actionNames(it.actions) }
        controller.sessionActivity?.creatorPackage?.let { details["sessionActivity"] = it }
        controller.queue?.let { queue ->
            details["queue"] = "${queue.size} items"
            queue.firstOrNull { it.queueId == playback?.activeQueueItemId }?.description?.let { d ->
                details["queueCurrent"] = "mediaId=${d.mediaId}, uri=${d.mediaUri}, title=${d.title}"
            }
        }
        controller.playbackInfo?.let { details["playbackType"] = if (it.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) "remote" else "local" }

        val state = playback?.state ?: PlaybackState.STATE_NONE
        return SessionSnapshot(
            packageName = controller.packageName,
            appLabel = label(controller.packageName),
            state = stateName(state),
            playing = state == PlaybackState.STATE_PLAYING,
            positionMs = playback?.position?.takeIf { it >= 0 },
            positionAtElapsedMs = playback?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = playback?.playbackSpeed?.takeIf { it > 0 } ?: 1f,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: metadata?.description?.title?.toString(),
            mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadata = fields,
            details = details,
        )
    }

    private fun label(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    private companion object {
        fun printable(metadata: MediaMetadata, key: String): String? {
            metadata.getString(key)?.let { return it.take(300) }
            metadata.getText(key)?.let { return it.toString().take(300) }
            metadata.getBitmap(key)?.let { b: Bitmap -> return "bitmap ${b.width}x${b.height}" }
            metadata.getRating(key)?.let { return "rating $it" }
            val n = metadata.getLong(key)
            return if (n != 0L) n.toString() else null
        }

        fun putBundle(into: MutableMap<String, String>, prefix: String, bundle: Bundle) {
            for (key in bundle.keySet().sorted()) {
                @Suppress("DEPRECATION")
                val value = runCatching { bundle.get(key) }.getOrNull() ?: continue
                into["$prefix.$key"] = value.toString().take(200)
            }
        }

        fun stateName(state: Int) = when (state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING -> "buffering"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_CONNECTING -> "connecting"
            PlaybackState.STATE_ERROR -> "error"
            PlaybackState.STATE_NONE -> "none"
            else -> "state $state"
        }

        fun actionNames(actions: Long): String = listOf(
            PlaybackState.ACTION_PLAY to "play",
            PlaybackState.ACTION_PAUSE to "pause",
            PlaybackState.ACTION_PLAY_PAUSE to "playPause",
            PlaybackState.ACTION_SEEK_TO to "seekTo",
            PlaybackState.ACTION_SKIP_TO_NEXT to "next",
            PlaybackState.ACTION_SKIP_TO_PREVIOUS to "previous",
            PlaybackState.ACTION_STOP to "stop",
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "playFromMediaId",
            PlaybackState.ACTION_PLAY_FROM_URI to "playFromUri",
        ).filter { actions and it.first != 0L }.joinToString(",") { it.second }
    }
}
