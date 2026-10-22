package dev.swipeto.sender.apps

import dev.swipeto.sender.handoff.LocalPlayback

/**
 * Another app's player (Samsung Video Player, YouTube...) as handoff sees it: position and play
 * state from its media session, pause and resume through its transport controls.
 */
class ExternalPlayback(private val watcher: ActiveMediaWatcher, val packageName: String) : LocalPlayback {
    override val positionMs: Long
        get() = watcher.session(packageName)?.positionNow() ?: 0L

    override val isPlaying: Boolean
        get() = watcher.session(packageName)?.playing == true

    override fun pause() {
        watcher.controller(packageName)?.transportControls?.pause()
    }

    override fun resumeAt(positionMs: Long, play: Boolean) {
        val controls = watcher.controller(packageName)?.transportControls ?: return
        controls.seekTo(positionMs)
        if (play) controls.play() else controls.pause()
    }
}
