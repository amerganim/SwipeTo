package dev.swipeto.protocol

enum class Role { SENDER, RECEIVER }

enum class SessionState { CONNECTED, PAIRING, PAIRED, SESSION, HANDOFF_ACTIVE }

/**
 * The v1 session state machine (docs/protocol.md, section 4) for one WebSocket connection, seen from
 * one side. Call [outgoing] before sending and [incoming] after parsing; both check the message is
 * allowed in the current state and then apply its transition.
 */
class ProtocolSession(val localRole: Role) {
    var state: SessionState = SessionState.CONNECTED
        private set

    val hasSession get() = state == SessionState.SESSION || state == SessionState.HANDOFF_ACTIVE

    /** Throws [IllegalStateException] if this side may not send [message] now. */
    fun outgoing(message: Message) {
        check(isAllowed(message, localRole, state)) { "$localRole may not send ${message.type} in state $state" }
        apply(message)
    }

    /** Throws [ProtocolException] (the code to reply with) if the peer may not send [message] now. */
    fun incoming(message: Message) {
        val author = if (localRole == Role.SENDER) Role.RECEIVER else Role.SENDER
        if (!isAllowed(message, author, state)) {
            val code = if (author == Role.SENDER && !hasSession && needsSession(message)) ErrorCode.NOT_PAIRED else ErrorCode.BAD_MESSAGE
            throw ProtocolException(code, "${message.type} is not allowed in state $state", message.id)
        }
        apply(message)
    }

    private fun apply(message: Message) {
        state = when {
            message is PairRequest -> SessionState.PAIRING
            message is PairResult && message.ok -> SessionState.PAIRED
            message is PairResult && (message.reason == PairFailure.CODE_EXPIRED || message.reason == PairFailure.TOO_MANY_ATTEMPTS) ->
                SessionState.CONNECTED
            message is HelloAck && message.accepted -> SessionState.SESSION
            message is HandoffAck && message.accepted -> SessionState.HANDOFF_ACTIVE
            message is Pullback -> SessionState.SESSION
            message is PlaybackState && message.ended && state == SessionState.HANDOFF_ACTIVE -> SessionState.SESSION
            else -> state
        }
    }

    companion object {
        fun isAllowed(message: Message, author: Role, state: SessionState): Boolean {
            if (message is Ping || message is Pong || message is ErrorMessage) return true
            return when (author) {
                Role.SENDER -> when (state) {
                    SessionState.CONNECTED, SessionState.PAIRED -> message is PairRequest || message is Hello
                    SessionState.PAIRING -> message is PairRequest || message is PairConfirm
                    SessionState.SESSION -> message is AimStart || message is AimEnd || message is Handoff
                    SessionState.HANDOFF_ACTIVE -> message is AimStart || message is AimEnd || message is Handoff ||
                        message is Control || message is PullbackRequest
                }
                Role.RECEIVER -> when (state) {
                    SessionState.CONNECTED, SessionState.PAIRED -> message is HelloAck
                    SessionState.PAIRING -> message is PairResult
                    SessionState.SESSION -> message is AimAck || message is HandoffAck || message is PlaybackState
                    SessionState.HANDOFF_ACTIVE -> message is AimAck || message is HandoffAck || message is PlaybackState || message is Pullback
                }
            }
        }

        private fun needsSession(message: Message) =
            message is AimStart || message is AimEnd || message is Handoff || message is Control || message is PullbackRequest
    }
}
