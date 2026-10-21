package dev.swipeto.sender.net

import dev.swipeto.protocol.ControlAction
import dev.swipeto.protocol.ErrorCode
import dev.swipeto.protocol.ErrorMessage
import dev.swipeto.protocol.MediaInfo
import dev.swipeto.protocol.MediaKind
import dev.swipeto.protocol.PairConfirm
import dev.swipeto.protocol.Ping
import dev.swipeto.protocol.PlaybackState
import dev.swipeto.protocol.Pong
import dev.swipeto.protocol.SessionState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicLong

class SenderConnectionTest {
    private val receiver = FakeReceiver()
    private val identity = SenderIdentity("a1b2c3d4-e5f6-4789-8abc-def012345678", "Test phone")
    private val video = MediaInfo(MediaKind.VIDEO, "http://10.0.0.2:8765/m/t/f", "video/mp4", "clip", 60_000)

    @AfterEach
    fun tearDown() = receiver.close()

    private suspend fun pairedConnection(): SenderConnection {
        val connection = SenderConnection.connect(receiver.url, identity)
        val token = connection.pair({ "123456" })!!
        assertTrue(connection.hello(token).accepted)
        return connection
    }

    @Test
    fun `pair, hello, handoff, control and pull-back`() = runBlocking {
        val connection = pairedConnection()
        assertEquals(SessionState.SESSION, connection.state)

        assertTrue(connection.handoff(video, 42_000, playing = true).accepted)
        assertEquals(SessionState.HANDOFF_ACTIVE, connection.state)
        assertEquals(42_000, waitForState(connection) { it.playing }.positionMs)

        connection.control(ControlAction.PAUSE)
        waitForState(connection) { !it.playing }
        connection.control(ControlAction.SEEK, 120_000)
        assertEquals(120_000, waitForState(connection) { it.positionMs == 120_000L }.positionMs)

        val back = connection.pullback()
        assertEquals(120_000, back.positionMs)
        assertFalse(back.playing)
        assertEquals(SessionState.SESSION, connection.state)
        connection.close()
    }

    @Test
    fun `wrong code is retried with the next attempt`() = runBlocking {
        val connection = SenderConnection.connect(receiver.url, identity)
        val attempts = mutableListOf<Int>()

        val token = connection.pair({ attempt -> attempts += attempt; if (attempt == 1) "000000" else "123456" })

        assertNotNull(token)
        assertEquals(listOf(1, 2), attempts)
        assertEquals(2, receiver.received.filterIsInstance<PairConfirm>().size)
        connection.close()
    }

    @Test
    fun `cancelling pairing returns null`() = runBlocking {
        val connection = SenderConnection.connect(receiver.url, identity)

        assertNull(connection.pair({ null }))
        connection.close()
    }

    @Test
    fun `unknown token is refused as not_paired`() = runBlocking {
        val connection = SenderConnection.connect(receiver.url, identity)

        val ack = connection.hello("AAAAAAAAAAAAAAAAAAAAAAAAAA")

        assertFalse(ack.accepted)
        assertEquals(ErrorCode.NOT_PAIRED, ack.reason)
        connection.close()
    }

    @Test
    fun `refused handoff carries the reason`() = runBlocking {
        val connection = pairedConnection()
        receiver.busy = true

        val ack = connection.handoff(video, 0, true)

        assertFalse(ack.accepted)
        assertEquals(ErrorCode.BUSY, ack.reason)
        connection.close()
    }

    @Test
    fun `answers ping and surfaces unsolicited errors`() = runBlocking {
        val connection = pairedConnection()

        val ping = Ping()
        receiver.push(ping)
        val error = ErrorMessage(ErrorCode.UNSUPPORTED_MEDIA, "could not load")
        val surfaced = async(start = CoroutineStart.UNDISPATCHED) { connection.events.filterIsInstance<ErrorMessage>().first() }
        receiver.push(error)

        assertEquals("could not load", withTimeout(5_000) { surfaced.await() }.description)
        withTimeout(5_000) { while (receiver.received.none { it is Pong && it.replyTo == ping.id }) kotlinx.coroutines.delay(20) }
        connection.close()
    }

    @Test
    fun `out-of-state sends are refused locally`() = runBlocking {
        val connection = SenderConnection.connect(receiver.url, identity)

        assertThrows<IllegalStateException> { connection.control(ControlAction.PLAY) }
        connection.close()
    }

    @Test
    fun `sends ping when quiet and closes after silence`() = runBlocking<Unit> {
        val now = AtomicLong(0)
        receiver.answerPings = false
        val connection = SenderConnection.connect(receiver.url, identity, clock = now::get)

        now.set(SenderConnection.PING_INTERVAL_MS + 1)
        withTimeout(5_000) { while (receiver.received.none { it is Ping }) kotlinx.coroutines.delay(20) }
        now.set(SenderConnection.SILENCE_TIMEOUT_MS + 1)

        withTimeout(5_000) { connection.closed.first { it } }
    }

    @Test
    fun `connection drop is reported`() = runBlocking<Unit> {
        val connection = pairedConnection()

        receiver.drop()

        withTimeout(5_000) { connection.closed.first { it } }
    }

    private suspend fun waitForState(connection: SenderConnection, predicate: (PlaybackState) -> Boolean): PlaybackState =
        withTimeout(5_000) { connection.lastState.first { it != null && predicate(it) }!! }
}
