package dev.swipeto.sender.apps

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import dev.swipeto.sender.handoff.LinkState

/** What the remote's buttons do: everything acts on the screen, never on the phone's player. */
interface RemoteActions {
    fun setPlaying(play: Boolean)
    fun seekTo(positionMs: Long)
    fun skip(deltaMs: Long)
    fun bringBack()
    fun stopOnScreen()

    /** Next (+1) or previous (-1) photo of an album on the screen. */
    fun page(step: Int)
}

/**
 * While a screen plays, the phone's standard media controls (notification shade, lock screen) work
 * the screen: "Playing on Laptop" with play/pause, seek bar, ±10 s, Bring back and Stop. Found on the
 * A15: after a swipe from the system video player there was no way to control or close the laptop
 * without opening SwipeTo.
 */
class RemoteSession(private val context: Context, private val actions: RemoteActions) {
    private var session: MediaSession? = null

    val token: MediaSession.Token? get() = session?.sessionToken

    /** Shows [remote] in the system media controls (null hides them); an [album] pages with next/previous. */
    fun update(remote: LinkState.Remote?, album: Boolean = false) {
        if (remote == null) {
            session?.release()
            session = null
            return
        }
        val s = session ?: MediaSession(context, "SwipeTo remote").also { created ->
            created.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = actions.setPlaying(true)
                override fun onPause() = actions.setPlaying(false)
                override fun onSeekTo(pos: Long) = actions.seekTo(pos)
                override fun onFastForward() = actions.skip(SKIP_MS)
                override fun onRewind() = actions.skip(-SKIP_MS)
                override fun onStop() = actions.stopOnScreen()
                override fun onSkipToNext() = actions.page(+1)
                override fun onSkipToPrevious() = actions.page(-1)
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action == BRING_BACK) actions.bringBack()
                }
            })
            created.isActive = true
            session = created
        }
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, remote.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Playing on ${remote.receiverName}")
                .apply { remote.durationMs?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) } }
                .build(),
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(if (remote.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, remote.positionMs, if (remote.playing) 1f else 0f)
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP or
                        (if (album) PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS else 0L),
                )
                .addCustomAction(PlaybackState.CustomAction.Builder(BRING_BACK, "Bring back to phone", android.R.drawable.ic_menu_revert).build())
                .build(),
        )
    }

    /**
     * The notification for [remote]: media style, so Android draws it as a player with the seek bar.
     * [intent] builds the PendingIntent for a service action.
     */
    fun notification(builder: Notification.Builder, remote: LinkState.Remote, album: Boolean, intent: (String) -> PendingIntent): Notification {
        fun action(icon: Int, title: String, name: String) = Notification.Action.Builder(Icon.createWithResource(context, icon), title, intent(name)).build()
        if (album) {
            // Photos: previous / next through the album, bring back, stop.
            return builder
                .setContentTitle(remote.title)
                .setContentText("Showing on ${remote.receiverName}")
                .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS))
                .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
                .addAction(action(android.R.drawable.ic_menu_revert, "Bring back", ACTION_BRING_BACK))
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP_SCREEN))
                .setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2))
                .build()
        }
        return builder
            .setContentTitle(remote.title)
            .setContentText("Playing on ${remote.receiverName}")
            .addAction(action(android.R.drawable.ic_media_rew, "Back 10 seconds", ACTION_REWIND))
            .addAction(
                if (remote.playing) action(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE)
                else action(android.R.drawable.ic_media_play, "Play", ACTION_PLAY),
            )
            .addAction(action(android.R.drawable.ic_media_ff, "Forward 10 seconds", ACTION_FORWARD))
            .addAction(action(android.R.drawable.ic_menu_revert, "Bring back", ACTION_BRING_BACK))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP_SCREEN))
            .setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 3))
            .build()
    }

    /** A notification button pressed: [name] is one of the ACTION_ constants. */
    fun handle(name: String): Boolean {
        when (name) {
            ACTION_PLAY -> actions.setPlaying(true)
            ACTION_PAUSE -> actions.setPlaying(false)
            ACTION_REWIND -> actions.skip(-SKIP_MS)
            ACTION_FORWARD -> actions.skip(SKIP_MS)
            ACTION_BRING_BACK -> actions.bringBack()
            ACTION_STOP_SCREEN -> actions.stopOnScreen()
            ACTION_NEXT -> actions.page(+1)
            ACTION_PREVIOUS -> actions.page(-1)
            else -> return false
        }
        return true
    }

    companion object {
        const val SKIP_MS = 10_000L
        const val BRING_BACK = "bring_back"
        const val ACTION_PLAY = "dev.swipeto.sender.remote.PLAY"
        const val ACTION_PAUSE = "dev.swipeto.sender.remote.PAUSE"
        const val ACTION_REWIND = "dev.swipeto.sender.remote.REWIND"
        const val ACTION_FORWARD = "dev.swipeto.sender.remote.FORWARD"
        const val ACTION_BRING_BACK = "dev.swipeto.sender.remote.BRING_BACK"
        const val ACTION_STOP_SCREEN = "dev.swipeto.sender.remote.STOP"
        const val ACTION_NEXT = "dev.swipeto.sender.remote.NEXT"
        const val ACTION_PREVIOUS = "dev.swipeto.sender.remote.PREVIOUS"
    }
}
