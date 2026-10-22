package dev.swipeto.sender.handoff

import dev.swipeto.protocol.MediaKind
import dev.swipeto.protocol.Transition
import dev.swipeto.sender.data.TrustTokens
import dev.swipeto.sender.media.MediaFile
import dev.swipeto.sender.net.FakeReceiver
import dev.swipeto.sender.net.SenderConnection
import dev.swipeto.sender.net.SenderIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress

class HandoffCoordinatorTest {
    private val receiver = FakeReceiver()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val trust = MemoryTrust()
    private val publisher = RecordingPublisher()
    private val local = FakePlayer(positionMs = 42_000, isPlaying = true)
    private val codes = ArrayDeque(listOf("123456"))
    private val target = Target("0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21", "Desk", receiver.url, InetAddress.getLoopbackAddress())
    private val media = SharedMedia(FakeFile, MediaKind.VIDEO, "Beach day", 180_000)

    private fun newCoordinator(stateTimeoutMs: Long = 60_000, reconnectWindowMs: Long = 0) = HandoffCoordinator(
        trust = trust,
        publisher = publisher,
        local = local,
        prompt = PairingPrompt { _, _ -> codes.removeFirstOrNull() },
        scope = scope,
        connect = { url -> SenderConnection.connect(url, SenderIdentity("a1b2c3d4-e5f6-4789-8abc-def012345678", "Phone")) },
        stateTimeoutMs = stateTimeoutMs,
        reconnectWindowMs = reconnectWindowMs,
        reconnectRetryMs = 200,
    )

    // FakeReceiver reports state on events only, not every second, so the default leaves the watchdog out of the way.
    private val coordinator = newCoordinator()

    @AfterEach
    fun tearDown() {
        scope.cancel()
        receiver.close()
    }

    @Test
    fun `first send pairs, hands off at the phone position and pauses locally`() = runBlocking {
        assertTrue(coordinator.sendTo(target, media))

        assertEquals(1, trust.tokens.size)
        val handoff = receiver.lastHandoff!!
        assertEquals(42_000, handoff.positionMs)
        assertTrue(handoff.playing)
        assertEquals("http://phone/m/1", handoff.media.url)
        assertEquals("Beach day", handoff.media.title)
        assertEquals(180_000L, handoff.media.durationMs)
        assertFalse(local.isPlaying)
        val remote = coordinator.state.value as LinkState.Remote
        assertEquals("Desk", remote.receiverName)
    }

    @Test
    fun `remote controls follow receiver state and bring-back resumes locally`() = runBlocking {
        coordinator.sendTo(target, media)

        coordinator.togglePlay() // pause
        awaitRemote { !it.playing }
        coordinator.seekTo(120_000)
        awaitRemote { it.positionMs == 120_000L }
        coordinator.togglePlay() // play
        awaitRemote { it.playing }

        coordinator.bringBack()

        assertEquals(LinkState.Idle, coordinator.state.value)
        assertEquals(120_000, local.positionMs)
        assertTrue(local.isPlaying)
        assertEquals(1, publisher.ended)
    }

    @Test
    fun `stored token skips pairing`() = runBlocking {
        coordinator.sendTo(target, media)
        coordinator.bringBack()
        codes.clear() // a second pairing would now fail

        assertTrue(coordinator.sendTo(target, media))
    }

    @Test
    fun `forgotten token is dropped and pairing runs again`() = runBlocking {
        trust.put(target.id, "stale_token_that_receiver_never_issued")

        assertTrue(coordinator.sendTo(target, media))

        assertTrue(trust.tokens[target.id]!!.startsWith("tok_"))
    }

    @Test
    fun `cancelled pairing leaves the phone playing`() = runBlocking {
        codes.clear()

        assertFalse(coordinator.sendTo(target, media))

        assertEquals(LinkState.Idle, coordinator.state.value)
        assertTrue(local.isPlaying)
        assertTrue(trust.tokens.isEmpty())
    }

    @Test
    fun `busy screen is reported and nothing changes locally`() = runBlocking {
        receiver.busy = true
        val notice = scope.asyncFirstNotice()

        assertFalse(coordinator.sendTo(target, media))

        assertTrue(local.isPlaying)
        assertEquals("Desk is busy with another phone.", withTimeout(5_000) { notice.await() })
    }

    @Test
    fun `unreachable screen is reported`() = runBlocking {
        val offline = target.copy(webSocketUrl = "ws://127.0.0.1:1/v1")

        assertFalse(coordinator.sendTo(offline, media))
        assertEquals(LinkState.Idle, coordinator.state.value)
    }

    @Test
    fun `viewer closing playback on the screen hands it back paused`() = runBlocking {
        coordinator.sendTo(target, media)
        awaitRemote { true }

        receiver.endPlayback()

        withTimeout(5_000) { coordinator.state.first { it == LinkState.Idle } }
        assertEquals(42_000, local.positionMs)
        assertFalse(local.isPlaying)
    }

    @Test
    fun `a dropped connection reconnects and hands off the same media where the screen should be`() = runBlocking {
        val resilient = newCoordinator(reconnectWindowMs = 5_000)
        assertTrue(resilient.sendTo(target, media))
        withTimeout(5_000) { resilient.state.first { it is LinkState.Remote && it.playing } }
        delay(300)

        receiver.drop()

        withTimeout(5_000) { resilient.state.first { it is LinkState.Remote && it.reconnecting } }
        val back = withTimeout(5_000) { resilient.state.first { it is LinkState.Remote && !it.reconnecting } } as LinkState.Remote
        assertEquals(2, receiver.connections)
        assertEquals(2, receiver.handoffs.size)
        assertEquals(receiver.handoffs[0].media.url, receiver.handoffs[1].media.url) // same URL: the screen re-attaches
        assertTrue(receiver.handoffs[1].positionMs in 42_200..44_000, "re-handed off at ${receiver.handoffs[1].positionMs}")
        assertTrue(back.playing)
        assertEquals(0, publisher.ended) // the file stayed served throughout
        assertFalse(local.isPlaying)

        // Still a working remote on the new connection.
        resilient.togglePlay()
        withTimeout(5_000) { resilient.state.first { it is LinkState.Remote && !it.playing } }
    }

    @Test
    fun `a screen that never comes back hands the video back to the phone after the reconnect window`() = runBlocking {
        val resilient = newCoordinator(reconnectWindowMs = 1_000)
        assertTrue(resilient.sendTo(target, media))
        withTimeout(5_000) { resilient.state.first { it is LinkState.Remote && it.playing } }

        receiver.close() // the screen is switched off: connecting fails from now on

        withTimeout(10_000) { resilient.state.first { it == LinkState.Idle } }
        assertTrue(local.isPlaying)
        assertTrue(local.positionMs >= 42_000)
        assertEquals(1, publisher.ended)
    }

    @Test
    fun `bring back while reconnecting resumes on the phone at once`() = runBlocking {
        val resilient = newCoordinator(reconnectWindowMs = 30_000)
        assertTrue(resilient.sendTo(target, media))
        withTimeout(5_000) { resilient.state.first { it is LinkState.Remote && it.playing } }
        receiver.close()
        withTimeout(5_000) { resilient.state.first { it is LinkState.Remote && it.reconnecting } }

        resilient.bringBack()

        assertEquals(LinkState.Idle, resilient.state.value)
        assertTrue(local.isPlaying)
    }

    @Test
    fun `album photos are paged on the screen with their transition`() = runBlocking {
        val first = SharedMedia(FakeFile, MediaKind.IMAGE, "IMG_1", null)
        val second = SharedMedia(FakeFile, MediaKind.IMAGE, "IMG_2", null)
        assertTrue(coordinator.sendTo(target, first))
        assertEquals(0, receiver.lastHandoff!!.positionMs)
        assertFalse(receiver.lastHandoff!!.playing)

        assertTrue(coordinator.show(second, Transition.NEXT))

        assertEquals(Transition.NEXT, receiver.lastHandoff!!.transition)
        assertEquals("IMG_2", receiver.lastHandoff!!.media.title)
        val remote = coordinator.state.value as LinkState.Remote
        assertEquals("IMG_2", remote.title)
        assertEquals(MediaKind.IMAGE, remote.kind)
    }

    @Test
    fun `album paging needs the screen to have the media`() = runBlocking {
        assertFalse(coordinator.show(media, Transition.NEXT))
    }

    @Test
    fun `losing the screen with reconnecting off continues on the phone near where it was`() = runBlocking {
        coordinator.sendTo(target, media)
        awaitRemote { it.playing }
        delay(300)

        receiver.drop()

        withTimeout(5_000) { coordinator.state.first { it == LinkState.Idle } }
        assertTrue(local.isPlaying)
        assertTrue(local.positionMs in 42_200..44_000, "resumed at ${local.positionMs}")
    }

    @Test
    fun `screen that stops reporting state is treated as gone`() = runBlocking {
        val watched = newCoordinator(stateTimeoutMs = 600)
        assertTrue(watched.sendTo(target, media))

        // FakeReceiver sends one state after the handoff and then nothing: like a screen that hung.
        withTimeout(5_000) { watched.state.first { it == LinkState.Idle } }
        assertTrue(local.isPlaying)
        assertTrue(local.positionMs >= 42_000)
    }

    @Test
    fun `a video that finished on the phone starts over on the screen`() = runBlocking {
        local.positionMs = 180_000
        local.isPlaying = false

        assertTrue(coordinator.sendTo(target, media))

        assertEquals(0, receiver.lastHandoff!!.positionMs)
        assertTrue(receiver.lastHandoff!!.playing)
    }

    private suspend fun awaitRemote(predicate: (LinkState.Remote) -> Boolean) =
        withTimeout(5_000) { coordinator.state.first { it is LinkState.Remote && predicate(it) } }

    private fun CoroutineScope.asyncFirstNotice() = async(start = CoroutineStart.UNDISPATCHED) { coordinator.notices.first() }

    private class MemoryTrust : TrustTokens {
        val tokens = mutableMapOf<String, String>()
        override fun get(receiverId: String) = tokens[receiverId]
        override fun put(receiverId: String, token: String) {
            tokens[receiverId] = token
        }
        override fun remove(receiverId: String) {
            tokens.remove(receiverId)
        }
    }

    private class RecordingPublisher : MediaPublisher {
        var published = 0
        var ended = 0
        override suspend fun publish(file: MediaFile, receiverHost: InetAddress) = "http://phone/m/${++published}"
        override fun endSession() {
            ended++
        }
    }

    private class FakePlayer(override var positionMs: Long, override var isPlaying: Boolean) : LocalPlayback {
        override fun pause() {
            isPlaying = false
        }
        override fun resumeAt(positionMs: Long, play: Boolean) {
            this.positionMs = positionMs
            isPlaying = play
        }
    }

    private object FakeFile : MediaFile {
        override val size = 10L
        override val mime = "video/mp4"
        override fun open(offset: Long) = ByteArrayInputStream(ByteArray(10))
    }
}
