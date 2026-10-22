package dev.swipeto.sender.handoff

import dev.swipeto.protocol.ControlAction
import dev.swipeto.protocol.ErrorCode
import dev.swipeto.protocol.ErrorMessage
import dev.swipeto.protocol.MediaInfo
import dev.swipeto.protocol.MediaKind
import dev.swipeto.protocol.PlaybackState
import dev.swipeto.protocol.SessionState
import dev.swipeto.protocol.Transition
import dev.swipeto.sender.data.TrustTokens
import dev.swipeto.sender.media.MediaFile
import dev.swipeto.sender.net.ReceiverException
import dev.swipeto.sender.net.SenderConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/** The phone's own player, as far as handoff needs it. */
interface LocalPlayback {
    val positionMs: Long
    val isPlaying: Boolean
    fun pause()
    fun resumeAt(positionMs: Long, play: Boolean)
}

/** Publishes a file on the phone's media server for a receiver at [receiverHost]; returns its URL. */
interface MediaPublisher {
    suspend fun publish(file: MediaFile, receiverHost: InetAddress): String
    fun endSession()
}

/** Asks the user for the code on the screen. Attempt 2+ means the last one was wrong. Null = cancelled. */
fun interface PairingPrompt {
    suspend fun askForCode(receiverName: String, attempt: Int): String?
}

data class Target(val id: String, val name: String, val webSocketUrl: String, val host: InetAddress)

/** A file the phone serves, or (with [app], and no file) another app's content handed off by reference. */
data class SharedMedia(val file: MediaFile?, val kind: MediaKind, val title: String, val durationMs: Long?, val app: MediaInfo? = null)

sealed interface LinkState {
    data object Idle : LinkState
    data class Connecting(val receiverName: String) : LinkState
    data class Pairing(val receiverName: String, val attempt: Int) : LinkState
    data class Remote(
        val receiverName: String,
        val title: String,
        val positionMs: Long,
        val playing: Boolean,
        val durationMs: Long?,
        val kind: MediaKind = MediaKind.VIDEO,
        /** The connection dropped; the screen carries on while the phone tries to get back to it. */
        val reconnecting: Boolean = false,
        /** Handed off, but the screen shows no frames yet: the phone keeps playing meanwhile. */
        val starting: Boolean = false,
    ) : LinkState
}

/**
 * Handoff: connect, pair if needed, hello, publish the file, hand off at the phone's current
 * position, then act as a remote until bring-back. Album photos are paged on the screen with [show].
 *
 * A playing video keeps playing on the phone until the screen reports frames showing (`state`
 * without `buffering`, docs/protocol.md 5.4), so nobody sees or hears a gap; only then does the
 * phone pause.
 *
 * If the connection drops while the screen has the media, the screen keeps playing and the phone
 * reconnects for up to [reconnectWindowMs], handing off the same URL at its estimated position (the
 * screen re-attaches without reloading, docs/protocol.md 5.4). Only if that fails does playback
 * continue on the phone from where the screen last was.
 *
 * Plain Kotlin, no Android types, so the whole flow is unit-tested against a fake receiver.
 */
class HandoffCoordinator(
    private val trust: TrustTokens,
    private val publisher: MediaPublisher,
    private val local: LocalPlayback,
    private val prompt: PairingPrompt,
    private val scope: CoroutineScope,
    private val connect: suspend (url: String) -> SenderConnection,
    private val clock: () -> Long = System::currentTimeMillis,
    /** A screen must report `state` every second while it plays; this long without one means it is gone. */
    private val stateTimeoutMs: Long = 6_000,
    private val reconnectWindowMs: Long = 20_000,
    private val reconnectRetryMs: Long = 1_000,
    private val overlapMaxMs: Long = 8_000,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Short messages for the user: refusals, lost connections. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private var connection: SenderConnection? = null
    private var watchers: Job? = null
    private var reconnect: Job? = null
    private val retryNow = Channel<Unit>(Channel.CONFLATED)
    private var lastStateAt = 0L

    /** Where the media is and what it is, for reconnecting and album paging. */
    private var target: Target? = null
    private var handedOff: MediaInfo? = null

    /**
     * Hands [media] to [target]. Returns true once the screen has it. [ready] is an already open,
     * greeted connection to that screen (from aiming), which skips connect and hello.
     */
    suspend fun sendTo(target: Target, media: SharedMedia, ready: SenderConnection? = null): Boolean {
        if (_state.value !is LinkState.Idle) {
            ready?.close()
            return false
        }
        _state.value = LinkState.Connecting(target.name)
        val greeted = ready?.takeIf { !it.closed.value && it.state == SessionState.SESSION }
        val conn = greeted ?: try {
            connect(target.webSocketUrl)
        } catch (e: Exception) {
            return fail("Can't reach ${target.name}. Is it on the same Wi-Fi?")
        }
        connection = conn
        try {
            if (greeted == null && !helloOrPair(conn, target)) return false

            // A video that already finished here starts over there.
            val finished = media.durationMs?.let { local.positionMs >= it - END_SLACK_MS } ?: false
            val timed = media.kind != MediaKind.IMAGE
            val playing = timed && (local.isPlaying || finished)
            val position = if (timed && !finished) local.positionMs else 0L
            val info = media.app ?: media.info(publisher.publish(media.file!!, target.host))
            val ack = conn.handoff(info, position, playing)
            if (!ack.accepted) return fail(refusal(target.name, ack.reason), conn)

            val overlap = playing && media.kind != MediaKind.IMAGE
            if (!overlap) local.pause()
            lastStateAt = clock()
            log("handed off to ${target.name} at $position ms, playing=$playing")
            this.target = target
            handedOff = info
            _state.value = LinkState.Remote(target.name, media.title, position, playing, media.durationMs, media.kind, starting = overlap)
            watch(conn, target.name)
            if (overlap) scope.launch {
                // A screen that never says it shows frames (an older one, or stuck): stop overlapping anyway.
                delay(overlapMaxMs)
                if ((_state.value as? LinkState.Remote)?.starting == true) shown("no frames reported after $overlapMaxMs ms")
            }
            return true
        } catch (e: ReceiverException) {
            return fail(e.code?.let { refusal(target.name, it) } ?: "Lost ${target.name}: ${e.message}", conn)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return fail("${target.name} did not answer.", conn)
        }
    }

    /**
     * Album paging while the screen has the media: shows [media] there, sliding in from the side
     * [transition] implies. A video starts from the beginning. False if the screen refused or is gone.
     */
    suspend fun show(media: SharedMedia, transition: Transition): Boolean {
        val remote = _state.value as? LinkState.Remote ?: return false
        val conn = connection?.takeIf { !remote.reconnecting } ?: return false
        val target = target ?: return false
        return try {
            val info = media.app ?: media.info(publisher.publish(media.file!!, target.host))
            val playing = media.kind != MediaKind.IMAGE
            val ack = conn.handoff(info, 0, playing, transition)
            if (!ack.accepted) {
                _notices.tryEmit(refusal(target.name, ack.reason))
                return false
            }
            handedOff = info
            lastStateAt = clock()
            log("showing ${media.title} on ${target.name} (${transition.wire})")
            _state.value = remote.copy(title = media.title, positionMs = 0, playing = playing, durationMs = media.durationMs, kind = media.kind)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("album handoff failed: $e")
            false
        }
    }

    fun togglePlay() {
        val remote = _state.value as? LinkState.Remote ?: return
        setPlaying(!remote.playing)
    }

    fun setPlaying(play: Boolean) {
        val remote = _state.value as? LinkState.Remote ?: return
        if (sendControl(if (play) ControlAction.PLAY else ControlAction.PAUSE)) _state.value = remote.copy(playing = play)
    }

    /** Closes playback on the screen; the phone stays paused where the screen stopped (its final `state`). */
    fun stopOnScreen() {
        if (_state.value !is LinkState.Remote) return
        sendControl(ControlAction.STOP)
    }

    fun seekTo(positionMs: Long) {
        val remote = _state.value as? LinkState.Remote ?: return
        if (sendControl(ControlAction.SEEK, positionMs.coerceAtLeast(0))) _state.value = remote.copy(positionMs = positionMs.coerceAtLeast(0))
    }

    /** Pulls the media back and resumes it on the phone where the screen was. */
    suspend fun bringBack() {
        val remote = _state.value as? LinkState.Remote ?: return
        val conn = connection
        val (position, playing) = try {
            val back = conn!!.pullback()
            back.positionMs to back.playing
        } catch (e: Exception) {
            // The screen didn't answer: continue from our best estimate.
            estimatedPosition(remote) to remote.playing
        }
        finishRemote(position, playing)
    }

    /** The app is going away: take the media back locally at once, no reconnecting. */
    fun takeBackNow() {
        val remote = _state.value as? LinkState.Remote ?: return
        finishRemote(estimatedPosition(remote), remote.playing)
    }

    /** The phone's Wi-Fi went away: drop the connection now (not after the 15 s keepalive) and start reconnecting. */
    fun onNetworkLost() {
        connection?.let { onLinkLost(it, "the phone's Wi-Fi went away") }
    }

    /** Wi-Fi is back: a reconnect in progress tries again now instead of after its pause. */
    fun onNetworkAvailable() {
        if ((_state.value as? LinkState.Remote)?.reconnecting == true) retryNow.trySend(Unit)
    }

    /** [conn] is gone or the screen stopped answering: reconnect, or continue here if that fails. */
    private fun onLinkLost(conn: SenderConnection, why: String) {
        val remote = _state.value as? LinkState.Remote ?: return
        if (connection !== conn || remote.reconnecting) return
        log("lost ${remote.receiverName}: $why")
        watchers?.cancel()
        watchers = null
        conn.close()
        connection = null
        val target = target
        val media = handedOff
        if (reconnectWindowMs <= 0 || target == null || media == null) return giveUp(remote)

        _state.value = remote.copy(reconnecting = true)
        retryNow.tryReceive() // a stale "Wi-Fi is back" from before this drop
        reconnect = scope.launch {
            val deadline = clock() + reconnectWindowMs
            var attempt = 0
            while (clock() < deadline) {
                attempt++
                val position = estimatedPosition(remote)
                val back = try {
                    rejoin(target, media, position, remote.playing)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("reconnect attempt $attempt to ${target.name} failed: ${e.message}")
                    null
                }
                if (back != null) {
                    connection = back
                    lastStateAt = clock()
                    log("reconnected to ${target.name} after $attempt attempt(s), re-handed off at $position ms")
                    _state.value = remote.copy(positionMs = position, reconnecting = false)
                    _notices.tryEmit("Reconnected to ${target.name}.")
                    watch(back, target.name)
                    return@launch
                }
                withTimeoutOrNull(reconnectRetryMs) { retryNow.receive() }
            }
            giveUp(_state.value as? LinkState.Remote ?: return@launch)
        }
    }

    /** Connect, hello and hand off the same media again; the screen re-attaches. Null if it refuses. */
    private suspend fun rejoin(target: Target, media: MediaInfo, positionMs: Long, playing: Boolean): SenderConnection? {
        val token = trust.get(target.id) ?: return null
        val conn = connect(target.webSocketUrl)
        val ok = try {
            withTimeout(REJOIN_TIMEOUT_MS) { conn.hello(token).accepted && conn.handoff(media, positionMs, playing).accepted }
        } catch (e: Exception) {
            conn.close()
            throw e
        }
        if (ok) return conn
        conn.close()
        return null
    }

    private fun giveUp(remote: LinkState.Remote) {
        log("continuing locally; ${remote.receiverName} is gone")
        _notices.tryEmit("Lost the connection to ${remote.receiverName}. Continuing here.")
        finishRemote(estimatedPosition(remote), remote.playing)
    }

    private suspend fun helloOrPair(conn: SenderConnection, target: Target): Boolean {
        var ack = trust.get(target.id)?.let { conn.hello(it) }
        if (ack == null || (!ack.accepted && ack.reason == ErrorCode.NOT_PAIRED)) {
            if (ack != null) trust.remove(target.id) // the screen forgot us (revoked or reinstalled)
            _state.value = LinkState.Pairing(target.name, 1)
            val token = conn.pair({ attempt ->
                _state.value = LinkState.Pairing(target.name, attempt)
                prompt.askForCode(target.name, attempt)
            }) ?: return fail("Pairing with ${target.name} didn't finish.", conn)
            trust.put(target.id, token)
            _state.value = LinkState.Connecting(target.name)
            ack = conn.hello(token)
        }
        return ack.accepted || fail(refusal(target.name, ack.reason), conn)
    }

    private fun watch(conn: SenderConnection, receiverName: String) {
        watchers?.cancel()
        watchers = scope.launch {
            launch {
                try {
                    conn.events.collect { message ->
                        when (message) {
                            is PlaybackState -> onState(message)
                            is ErrorMessage -> _notices.tryEmit("$receiverName: ${message.description}")
                            else -> Unit
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never leave the phone showing a remote that nothing updates any more.
                    log("state handling failed: $e")
                    onLinkLost(conn, "state handling failed")
                }
            }
            launch {
                // The latest state, not the event stream: the first one can arrive before we listen.
                val first = conn.lastState.first { it != null && it.playing && !it.buffering && !it.ended }!!
                shown("screen shows frames at ${first.positionMs} ms")
            }
            launch {
                conn.closed.first { it }
                log("connection to $receiverName closed")
                onLinkLost(conn, "connection closed")
            }
            launch {
                while (_state.value is LinkState.Remote && connection === conn) {
                    delay(stateTimeoutMs / 4)
                    if (_state.value is LinkState.Remote && connection === conn && clock() - lastStateAt > stateTimeoutMs) {
                        onLinkLost(conn, "no state for ${clock() - lastStateAt} ms")
                    }
                }
            }
        }
    }

    private fun onState(state: PlaybackState) {
        val remote = _state.value as? LinkState.Remote ?: return
        lastStateAt = clock()
        if (state.ended) {
            // Closed on the screen (or finished): the media is back here, paused where it stopped.
            _notices.tryEmit("${remote.receiverName} stopped playing.")
            finishRemote(state.positionMs, play = false)
        } else {
            _state.value = remote.copy(positionMs = state.positionMs, playing = state.playing)
        }
    }

    /** The screen took over the picture: the phone stops playing. */
    private fun shown(why: String) {
        val remote = _state.value as? LinkState.Remote ?: return
        if (!remote.starting) return
        local.pause()
        _state.value = remote.copy(starting = false)
        log("phone paused: $why")
    }

    private fun estimatedPosition(remote: LinkState.Remote): Long {
        val drift = if (remote.playing) clock() - lastStateAt else 0
        val estimate = remote.positionMs + drift
        return remote.durationMs?.let { estimate.coerceAtMost(it) } ?: estimate
    }

    private fun sendControl(action: ControlAction, positionMs: Long? = null): Boolean = try {
        connection?.control(action, positionMs)
        connection != null
    } catch (e: IllegalStateException) {
        false
    }

    private fun finishRemote(positionMs: Long, play: Boolean) {
        reconnect?.cancel()
        reconnect = null
        watchers?.cancel()
        watchers = null
        target = null
        handedOff = null
        connection?.close()
        connection = null
        publisher.endSession()
        local.resumeAt(positionMs, play)
        _state.value = LinkState.Idle
    }

    private fun fail(notice: String, conn: SenderConnection? = null): Boolean {
        conn?.close()
        if (connection === conn) connection = null
        publisher.endSession()
        _notices.tryEmit(notice)
        _state.value = LinkState.Idle
        return false
    }

    private companion object {
        /** Within this of the end counts as finished. */
        const val END_SLACK_MS = 500L

        /** One reconnect attempt: hello and handoff answered within this. */
        const val REJOIN_TIMEOUT_MS = 5_000L
    }

    private fun refusal(name: String, reason: ErrorCode?) = when (reason) {
        ErrorCode.BUSY -> "$name is busy with another phone."
        ErrorCode.UNSUPPORTED_MEDIA -> "$name can't play this file. Try another video or photo."
        ErrorCode.VERSION_MISMATCH -> "$name needs a different SwipeTo version."
        ErrorCode.NOT_PAIRED -> "$name doesn't recognise this phone. Pair again."
        ErrorCode.SERVICE_UNAVAILABLE -> "$name couldn't find this video on YouTube. Check its YouTube API key in its Settings."
        else -> "$name said no."
    }

    private fun SharedMedia.info(url: String) = MediaInfo(kind, url, file!!.mime, title, durationMs)
}
