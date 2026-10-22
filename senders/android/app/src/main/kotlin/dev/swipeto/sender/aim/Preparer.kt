package dev.swipeto.sender.aim

import dev.swipeto.protocol.Prepare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * While a video plays on the phone, keeps the screen a swipe would most likely go to loading it,
 * hidden and in step (`prepare`, docs/protocol.md 5.3), so the handoff starts at once. Over the
 * A15's Wi-Fi a screen needs 2–2.5 s to buffer a 9 Mbit/s video, longer than people take between
 * raising the phone and swiping, so an aim-time prefetch alone starts too late.
 *
 * Uses only a standby link that is already open and greeted ([StandbyLinks.ready]); it never
 * connects by itself, and never to an unpaired screen.
 */
class Preparer(
    private val scope: CoroutineScope,
    private val links: StandbyLinks,
    /** The screen to prepare, or null for none (nothing used before, preparing turned off...). */
    private val target: () -> LinkTarget?,
    /** What is playing and where, published for [LinkTarget]; null while nothing worth preparing plays. */
    private val what: suspend (LinkTarget) -> Prepare?,
    private val intervalMs: Long = INTERVAL_MS,
    private val log: (String) -> Unit = {},
) {
    private var job: Job? = null
    private var preparing: Pair<String, String>? = null // screen id to media url, for the log

    /** Running (whether or not anything plays right now). */
    val active: Boolean get() = job?.isActive == true

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                tick()
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        preparing = null
    }

    private suspend fun tick() {
        val screen = target() ?: return
        val conn = links.ready(screen.id) ?: return
        val prepare = runCatching { what(screen) }.getOrNull() ?: return
        if (runCatching { conn.send(prepare) }.isFailure) return
        val now = screen.id to prepare.media.url
        if (now != preparing) log("preparing ${prepare.media.title} on ${screen.name} at ${prepare.positionMs} ms")
        preparing = now
    }

    companion object {
        /** A screen drops what it prepared 10 s after the last `prepare`. */
        const val INTERVAL_MS = 4_000L
    }
}
