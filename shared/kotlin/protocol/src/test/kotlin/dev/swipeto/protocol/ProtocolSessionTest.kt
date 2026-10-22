package dev.swipeto.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolSessionTest {
    private val senderId = "a1b2c3d4-e5f6-4789-8abc-def012345678"
    private val receiverId = "0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21"
    private val token = "q8Zr3kP0vX2mN7bT5wY1cA9dE4fG6hJ"
    private val video = MediaInfo(MediaKind.VIDEO, "http://10.0.0.2:8765/m/tok/f1", "video/mp4", "clip")

    private val phone = ProtocolSession(Role.SENDER)
    private val screen = ProtocolSession(Role.RECEIVER)

    /** Drives both ends with the same message so their states must stay in step. */
    private fun exchange(message: Message, author: Role) {
        val (from, to) = if (author == Role.SENDER) phone to screen else screen to phone
        from.outgoing(message)
        to.incoming(message)
        assertEquals(phone.state, screen.state)
    }

    @Test
    fun `full flow walks every state`() {
        exchange(PairRequest(senderId, "Pixel"), Role.SENDER)
        assertEquals(SessionState.PAIRING, phone.state)
        exchange(PairConfirm("111111"), Role.SENDER)
        exchange(PairResult.failure(PairFailure.WRONG_CODE), Role.RECEIVER)
        assertEquals(SessionState.PAIRING, phone.state)
        exchange(PairConfirm("048213"), Role.SENDER)
        exchange(PairResult.success(token), Role.RECEIVER)
        assertEquals(SessionState.PAIRED, phone.state)
        exchange(Hello(senderId, "Pixel", token, ProtocolVersion.CURRENT), Role.SENDER)
        exchange(HelloAck(receiverId, true), Role.RECEIVER)
        assertEquals(SessionState.SESSION, phone.state)
        exchange(AimStart(), Role.SENDER)
        exchange(Handoff(video, 462_000, true), Role.SENDER)
        exchange(HandoffAck(true), Role.RECEIVER)
        assertEquals(SessionState.HANDOFF_ACTIVE, phone.state)
        exchange(Control(ControlAction.PAUSE), Role.SENDER)
        exchange(PlaybackState(462_500, false, false), Role.RECEIVER)
        exchange(Control(ControlAction.SEEK, 600_000), Role.SENDER)
        exchange(PullbackRequest(), Role.SENDER)
        exchange(Pullback(600_000, false), Role.RECEIVER)
        assertEquals(SessionState.SESSION, phone.state)
    }

    @Test
    fun `ended state returns to session and late state is tolerated`() {
        phone.outgoing(Hello(senderId, "Pixel", token, 1))
        phone.incoming(HelloAck(receiverId, true))
        phone.outgoing(Handoff(video, 0, true))
        phone.incoming(HandoffAck(true))

        phone.incoming(PlaybackState(1000, false, true))
        assertEquals(SessionState.SESSION, phone.state)
        phone.incoming(PlaybackState(1000, false, true))
        assertEquals(SessionState.SESSION, phone.state)
    }

    @Test
    fun `expired code returns to connected`() {
        screen.incoming(PairRequest(senderId, "Pixel"))
        screen.outgoing(PairResult.failure(PairFailure.CODE_EXPIRED))

        assertEquals(SessionState.CONNECTED, screen.state)
    }

    @Test
    fun `refused hello stays connected`() {
        phone.outgoing(Hello(senderId, "Pixel", token, 1))
        phone.incoming(HelloAck(receiverId, false, ErrorCode.NOT_PAIRED))

        assertEquals(SessionState.CONNECTED, phone.state)
    }

    @Test
    fun `session messages before hello are not_paired`() {
        val error = assertFailsWith<ProtocolException> { screen.incoming(Handoff(video, 0, true)) }

        assertEquals(ErrorCode.NOT_PAIRED, error.code)
    }

    @Test
    fun `prepare is for a session without an active handoff`() {
        assertEquals(ErrorCode.NOT_PAIRED, assertFailsWith<ProtocolException> { screen.incoming(Prepare(video, 1_000)) }.code)

        screen.incoming(Hello(senderId, "Pixel", token, 1))
        screen.outgoing(HelloAck(receiverId, true))
        screen.incoming(Prepare(video, 1_000))
        screen.incoming(Handoff(video, 1_000, true))
        screen.outgoing(HandoffAck(true))

        assertEquals(ErrorCode.BAD_MESSAGE, assertFailsWith<ProtocolException> { screen.incoming(Prepare(video, 2_000)) }.code)
    }

    @Test
    fun `control without active handoff is bad_message`() {
        screen.incoming(Hello(senderId, "Pixel", token, 1))
        screen.outgoing(HelloAck(receiverId, true))

        val error = assertFailsWith<ProtocolException> { screen.incoming(Control(ControlAction.PLAY)) }

        assertEquals(ErrorCode.BAD_MESSAGE, error.code)
    }

    @Test
    fun `receiver only messages cannot come from the sender`() {
        assertFailsWith<ProtocolException> { screen.incoming(HelloAck(receiverId, true)) }
        assertFailsWith<IllegalStateException> { screen.outgoing(Hello(senderId, "x", token, 1)) }
    }

    @Test
    fun `keepalive and errors are allowed everywhere`() {
        for (state in SessionState.entries) {
            for (role in Role.entries) {
                assertTrue(ProtocolSession.isAllowed(Ping(), role, state))
                assertTrue(ProtocolSession.isAllowed(Pong(), role, state))
                assertTrue(ProtocolSession.isAllowed(ErrorMessage(ErrorCode.BUSY, "busy"), role, state))
            }
        }
    }

    @Test
    fun `aim_ack answers a lock only once in a session`() {
        assertFailsWith<ProtocolException> { phone.incoming(AimAck(true)) }

        phone.outgoing(Hello(senderId, "Pixel", token, 1))
        phone.incoming(HelloAck(receiverId, true))
        phone.outgoing(AimStart("Qm9vVGhlU2NyZWVu"))
        phone.incoming(AimAck(true))

        assertEquals(SessionState.SESSION, phone.state)
    }

    @Test
    fun `version check`() {
        assertTrue(ProtocolVersion.isSupported(1))
        assertFalse(ProtocolVersion.isSupported(2))
    }
}
