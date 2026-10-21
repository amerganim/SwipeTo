package dev.swipeto.sender.handoff

import dev.swipeto.protocol.ControlAction
import dev.swipeto.protocol.ErrorCode
import dev.swipeto.protocol.ErrorMessage
import dev.swipeto.protocol.MediaInfo
import dev.swipeto.protocol.MediaKind
import dev.swipeto.protocol.PlaybackState
import dev.swipeto.sender.data.TrustTokens
import dev.swipeto.sender.media.MediaFile
import dev.swipeto.sender.net.ReceiverException
import dev.swipeto.sender.net.SenderConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

data class SharedMedia(val file: MediaFile, val kind: MediaKind, val title: String, val durationMs: Long?)

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
    ) : LinkState
}

/**
 * Button-triggered handoff (Phase 3): connect, pair if needed, hello, publish the file, hand off at the
 * phone's current position, pause locally, then act as a remote until bring-back. If the screen goes
 * away while it has the media, playback continues on the phone from where the screen last was.
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
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Short messages for the user: refusals, lost connections. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private var connection: SenderConnection? = null
    private var watchers: Job? = null
    private var lastStateAt = 0L

    /** Hands [media] to [target]. Returns true once the screen has it. */
    suspend fun sendTo(target: Target, media: SharedMedia): Boolean {
        if (_state.value !is LinkState.Idle) return false
        _state.value = LinkState.Connecting(target.name)
        val conn = try {
            connect(target.webSocketUrl)
        } catch (e: Exception) {
            return fail("Can't reach ${target.name}. Is it on the same Wi-Fi?")
        }
        connection = conn
        try {
            if (!helloOrPair(conn, target)) return false

            val playing = media.kind == MediaKind.VIDEO && local.isPlaying
            val position = if (media.kind == MediaKind.VIDEO) local.positionMs else 0L
            val url = publisher.publish(media.file, target.host)
            val ack = conn.handoff(MediaInfo(media.kind, url, media.file.mime, media.title, media.durationMs), position, playing)
            if (!ack.accepted) return fail(refusal(target.name, ack.reason), conn)

            local.pause()
            lastStateAt = clock()
            _state.value = LinkState.Remote(target.name, media.title, position, playing, media.durationMs)
            watch(conn, target.name)
            return true
        } catch (e: ReceiverException) {
            return fail(e.code?.let { refusal(target.name, it) } ?: "Lost ${target.name}: ${e.message}", conn)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return fail("${target.name} did not answer.", conn)
        }
    }

    fun togglePlay() {
        val remote = _state.value as? LinkState.Remote ?: return
        val action = if (remote.playing) ControlAction.PAUSE else ControlAction.PLAY
        if (sendControl(action)) _state.value = remote.copy(playing = !remote.playing)
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

    /** The phone left the network (or the app is going away): take the media back locally. */
    fun onConnectionLost() {
        val remote = _state.value as? LinkState.Remote ?: return
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
                conn.events.collect { message ->
                    when (message) {
                        is PlaybackState -> onState(message)
                        is ErrorMessage -> _notices.tryEmit("$receiverName: ${message.description}")
                        else -> Unit
                    }
                }
            }
            launch {
                conn.closed.first { it }
                if (_state.value is LinkState.Remote && connection === conn) onConnectionLost()
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
        watchers?.cancel()
        watchers = null
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

    private fun refusal(name: String, reason: ErrorCode?) = when (reason) {
        ErrorCode.BUSY -> "$name is busy with another phone."
        ErrorCode.UNSUPPORTED_MEDIA -> "$name can't play this file."
        ErrorCode.VERSION_MISMATCH -> "$name needs a different SwipeTo version."
        ErrorCode.NOT_PAIRED -> "$name doesn't recognise this phone. Pair again."
        else -> "$name said no."
    }
}
