package dev.swipeto.protocol

import java.util.UUID

/** Wire names of every v1 message type (docs/protocol.md, section 5). */
object MessageTypes {
    const val PAIR_REQUEST = "pair_request"
    const val PAIR_CONFIRM = "pair_confirm"
    const val PAIR_RESULT = "pair_result"
    const val HELLO = "hello"
    const val HELLO_ACK = "hello_ack"
    const val AIM_START = "aim_start"
    const val AIM_END = "aim_end"
    const val AIM_ACK = "aim_ack"
    const val PREPARE = "prepare"
    const val HANDOFF = "handoff"
    const val HANDOFF_ACK = "handoff_ack"
    const val CONTROL = "control"
    const val STATE = "state"
    const val PULLBACK_REQUEST = "pullback_request"
    const val PULLBACK = "pullback"
    const val PING = "ping"
    const val PONG = "pong"
    const val ERROR = "error"

    val ALL = listOf(
        PAIR_REQUEST, PAIR_CONFIRM, PAIR_RESULT, HELLO, HELLO_ACK, AIM_START, AIM_END, AIM_ACK, PREPARE, HANDOFF,
        HANDOFF_ACK, CONTROL, STATE, PULLBACK_REQUEST, PULLBACK, PING, PONG, ERROR,
    )
}

object ProtocolVersion {
    const val CURRENT = 1

    fun isSupported(version: Int) = version == CURRENT
}

fun newMessageId(): String = UUID.randomUUID().toString()

fun nowMs(): Long = System.currentTimeMillis()

/**
 * Envelope shared by every message. [id] and [ts] are filled in on creation; [replyTo] is set on a
 * response to the [id] of the request it answers.
 */
sealed class Message {
    abstract val type: String
    abstract val id: String
    abstract val ts: Long
    abstract val replyTo: String?
}

data class PairRequest(
    val senderId: String,
    val senderName: String,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.PAIR_REQUEST
}

data class PairConfirm(
    val code: String,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.PAIR_CONFIRM
}

/** Exactly one of [trustToken] (when [ok]) or [reason] is set. */
data class PairResult(
    val ok: Boolean,
    val trustToken: String? = null,
    val reason: PairFailure? = null,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.PAIR_RESULT

    companion object {
        fun success(trustToken: String, replyTo: String? = null) = PairResult(true, trustToken, null, replyTo = replyTo)
        fun failure(reason: PairFailure, replyTo: String? = null) = PairResult(false, null, reason, replyTo = replyTo)
    }
}

data class Hello(
    val senderId: String,
    val senderName: String,
    val trustToken: String,
    val protocolVersion: Int,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.HELLO
}

data class HelloAck(
    val receiverId: String,
    val accepted: Boolean,
    val reason: ErrorCode? = null,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.HELLO_ACK
}

/**
 * With [nonce]: the marker nonce the phone's camera read, asking the receiver to confirm the lock.
 * With [prefetch]: what is about to be handed off, so the receiver can start loading it.
 */
data class AimStart(
    val nonce: String? = null,
    val prefetch: Prefetch? = null,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.AIM_START
}

/** Answer to an `aim_start` with a nonce: locked if it is the receiver's current or previous marker nonce. */
data class AimAck(
    val locked: Boolean,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.AIM_ACK
}

data class AimEnd(
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.AIM_END
}

/** A hint to open [media] at [positionMs] before the `handoff` arrives. */
data class Prefetch(val media: MediaInfo, val positionMs: Long)

/** While the user watches: open [media] hidden and muted, in step with [positionMs]. */
data class Prepare(
    val media: MediaInfo,
    val positionMs: Long,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.PREPARE
}

/**
 * A file the phone serves ([MediaKind.VIDEO], [MediaKind.IMAGE]), or another app's content
 * ([MediaKind.APP]): then [url] is the optional `contentUrl` (empty if none), [mime] is empty and
 * [service] is set.
 */
data class MediaInfo(
    val kind: MediaKind,
    val url: String,
    val mime: String,
    val title: String,
    val durationMs: Long? = null,
    val service: String? = null,
    val contentId: String? = null,
    val channel: String? = null,
) {
    val isApp: Boolean get() = kind == MediaKind.APP

    companion object {
        fun app(service: String, title: String, channel: String? = null, contentId: String? = null, contentUrl: String? = null, durationMs: Long? = null) =
            MediaInfo(MediaKind.APP, contentUrl ?: "", "", title, durationMs, service, contentId, channel)
    }
}

/** [transition]: the media follows on from what is showing (album navigation). */
data class Handoff(
    val media: MediaInfo,
    val positionMs: Long,
    val playing: Boolean,
    val transition: Transition? = null,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.HANDOFF
}

data class HandoffAck(
    val accepted: Boolean,
    val reason: ErrorCode? = null,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.HANDOFF_ACK
}

/** [positionMs] is required for [ControlAction.SEEK] and ignored otherwise. */
data class Control(
    val action: ControlAction,
    val positionMs: Long? = null,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.CONTROL
}

/** The `state` message: receiver playback state. [playing] is the intent; [buffering] says no frames are showing yet. */
data class PlaybackState(
    val positionMs: Long,
    val playing: Boolean,
    val ended: Boolean,
    val buffering: Boolean = false,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.STATE
}

data class PullbackRequest(
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.PULLBACK_REQUEST
}

data class Pullback(
    val positionMs: Long,
    val playing: Boolean,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.PULLBACK
}

data class Ping(
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.PING
}

data class Pong(
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.PONG
}

/** The `error` message. [description] is the wire field `message`. */
data class ErrorMessage(
    val code: ErrorCode,
    val description: String,
    override val id: String = newMessageId(),
    override val ts: Long = nowMs(),
    override val replyTo: String? = null,
) : Message() {
    override val type get() = MessageTypes.ERROR
}
