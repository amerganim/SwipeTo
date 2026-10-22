package dev.swipeto.sender.aim

import dev.swipeto.protocol.MediaInfo
import dev.swipeto.protocol.MediaKind
import dev.swipeto.protocol.Prepare
import dev.swipeto.sender.data.TrustTokens
import dev.swipeto.sender.net.FakeReceiver
import dev.swipeto.sender.net.SenderConnection
import dev.swipeto.sender.net.SenderIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreparerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val desk = FakeReceiver(receiverId = "0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21")
    private val deskTarget = LinkTarget("0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21", "Desk", desk.url)
    private val trust = object : TrustTokens {
        val tokens = mutableMapOf(deskTarget.id to "desk_token_abcdefghijklmnopq")
        override fun get(receiverId: String) = tokens[receiverId]
        override fun put(receiverId: String, token: String) {
            tokens[receiverId] = token
        }
        override fun remove(receiverId: String) {
            tokens.remove(receiverId)
        }
    }
    private val links = StandbyLinks(scope, trust, { url -> SenderConnection.connect(url, SenderIdentity("a1b2c3d4-e5f6-4789-8abc-def012345678", "Phone")) })
    private val clip = MediaInfo(MediaKind.VIDEO, "http://phone/m/s/clip", "video/mp4", "Clip", 180_000)
    private var position = 40_000L
    private var playing = true

    init {
        desk.issuedTokens += "desk_token_abcdefghijklmnopq"
    }

    @AfterEach
    fun tearDown() {
        runBlocking { links.closeAll() }
        scope.cancel()
        desk.close()
    }

    private fun preparer(target: LinkTarget? = deskTarget) = Preparer(
        scope, links, { target },
        what = { if (playing) Prepare(clip, position) else null },
        intervalMs = 150,
    )

    private fun prepares() = desk.received.filterIsInstance<Prepare>()

    @Test
    fun `while a video plays, the screen gets prepare repeatedly at the current position`() = runBlocking<Unit> {
        links.warm(listOf(deskTarget))
        withTimeout(5_000) { while (links.ready(deskTarget.id) == null) delay(20) }
        val preparer = preparer()

        preparer.start()
        withTimeout(5_000) { while (prepares().isEmpty()) delay(20) }
        position = 41_000
        withTimeout(5_000) { while (prepares().none { it.positionMs == 41_000L }) delay(20) }
        preparer.stop()

        assertEquals(clip.url, prepares().first().media.url)
        assertTrue(prepares().size >= 2)
        assertTrue(!desk.glowing) // preparing never makes the screen glow
    }

    @Test
    fun `nothing is sent while paused, without a link, or with no screen to prepare`() = runBlocking<Unit> {
        playing = false
        links.warm(listOf(deskTarget))
        withTimeout(5_000) { while (links.ready(deskTarget.id) == null) delay(20) }
        val paused = preparer().also { it.start() }
        delay(500)
        paused.stop()

        playing = true
        val none = preparer(target = null).also { it.start() }
        delay(500)
        none.stop()

        links.closeAll()
        val noLink = preparer().also { it.start() }
        delay(500)
        noLink.stop()

        assertEquals(0, prepares().size)
        assertEquals(1, desk.connections) // never connects by itself
    }
}
