package dev.swipeto.sender.aim

import dev.swipeto.protocol.Hello
import dev.swipeto.protocol.MediaKind
import dev.swipeto.sender.data.TrustTokens
import dev.swipeto.sender.handoff.HandoffCoordinator
import dev.swipeto.sender.handoff.LocalPlayback
import dev.swipeto.sender.handoff.MediaPublisher
import dev.swipeto.sender.handoff.PairingPrompt
import dev.swipeto.sender.handoff.SharedMedia
import dev.swipeto.sender.handoff.Target
import dev.swipeto.sender.media.MediaFile
import dev.swipeto.sender.net.FakeReceiver
import dev.swipeto.sender.net.SenderConnection
import dev.swipeto.sender.net.SenderIdentity
import kotlinx.coroutines.CoroutineScope
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

class AimControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val desk = FakeReceiver(receiverId = "0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21", markerNonce = "DeskMarkerNonce0001")
    private val tv = FakeReceiver(receiverId = "9f8e7d6c-5b4a-4321-8fed-cba987654321", markerNonce = "TvMarkerNonce000001")
    private val deskTarget = LinkTarget("0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21", "Desk", desk.url)
    private val tvTarget = LinkTarget("9f8e7d6c-5b4a-4321-8fed-cba987654321", "TV", tv.url)
    private val trust = MemoryTrust()
    private val identity = SenderIdentity("a1b2c3d4-e5f6-4789-8abc-def012345678", "Phone")
    private val links = StandbyLinks(scope, trust, { url -> SenderConnection.connect(url, identity) })
    private var seen: MarkerPayload? = null
    private val camera = CameraMarkerResolver(MarkerScanner { _, accept -> seen?.takeIf(accept) }, timeoutMs = { 300 })

    init {
        desk.issuedTokens += "desk_token_abcdefghijklmnopq"
        tv.issuedTokens += "tv_token_abcdefghijklmnopqrst"
        trust.put(deskTarget.id, "desk_token_abcdefghijklmnopq")
        trust.put(tvTarget.id, "tv_token_abcdefghijklmnopqrst")
    }

    @AfterEach
    fun tearDown() {
        runBlocking { links.closeAll() }
        scope.cancel()
        desk.close()
        tv.close()
    }

    private fun controller(screens: List<Pair<LinkTarget, Long?>>) =
        AimController(scope, links, { screens }, ChainResolver(listOf(camera, FallbackResolver)))

    private suspend fun AimController.settled(): AimState = withTimeout(5_000) {
        state.first { it is AimState.Targeted || it is AimState.NoScreens }
    }

    @Test
    fun `camera marker picks the screen in front, it locks, the other stops glowing`() = runBlocking {
        seen = MarkerPayload(tvTarget.id, "TvMarkerNonce000001")
        val aim = controller(listOf(deskTarget to null, tvTarget to null))

        aim.onAimStart()
        val targeted = aim.settled() as AimState.Targeted

        assertEquals("TV", targeted.target.name)
        assertEquals(ResolvedBy.CAMERA, targeted.by)
        assertTrue(targeted.verified)
        assertTrue(tv.locked)
        withTimeout(5_000) { while (desk.glowing) delay(20) }
        assertTrue(tv.glowing)
    }

    @Test
    fun `stale marker still targets but is not verified`() = runBlocking {
        seen = MarkerPayload(deskTarget.id, "PhotoOfAnOldMarker01")
        val aim = controller(listOf(deskTarget to null, tvTarget to null))

        aim.onAimStart()
        val targeted = aim.settled() as AimState.Targeted

        assertEquals("Desk", targeted.target.name)
        assertFalse(targeted.verified)
        assertFalse(desk.locked)
    }

    @Test
    fun `without a marker, the only screen is the target`() = runBlocking {
        val aim = controller(listOf(deskTarget to null))

        aim.onAimStart()
        val targeted = aim.settled() as AimState.Targeted

        assertEquals(ResolvedBy.ONLY_SCREEN, targeted.by)
        assertTrue(desk.glowing)
    }

    @Test
    fun `without a marker and with several screens, the most recently used wins`() = runBlocking {
        val aim = controller(listOf(deskTarget to 1_000L, tvTarget to 5_000L))

        aim.onAimStart()

        assertEquals("TV", (aim.settled() as AimState.Targeted).target.name)
    }

    @Test
    fun `lowering the phone ends the aim everywhere`() = runBlocking {
        val aim = controller(listOf(deskTarget to null))
        aim.onAimStart()
        aim.settled()

        aim.onAimEnd()

        withTimeout(5_000) { while (desk.glowing) delay(20) }
        assertEquals(AimState.Idle, aim.state.value)
    }

    @Test
    fun `no paired screen reachable`() = runBlocking {
        val offline = LinkTarget(deskTarget.id, "Desk", "ws://127.0.0.1:1/v1")
        val aim = controller(listOf(offline to null))

        aim.onAimStart()

        assertEquals(AimState.NoScreens, aim.settled())
    }

    @Test
    fun `swipe-up hands off over the aimed connection without a second hello`() = runBlocking {
        val aim = controller(listOf(deskTarget to null))
        aim.onAimStart()
        aim.settled()
        val (target, conn) = aim.takeTarget()!!
        val local = object : LocalPlayback {
            override var positionMs = 30_000L
            override var isPlaying = true
            override fun pause() { isPlaying = false }
            override fun resumeAt(positionMs: Long, play: Boolean) = Unit
        }
        val coordinator = HandoffCoordinator(
            trust = trust,
            publisher = object : MediaPublisher {
                override suspend fun publish(file: MediaFile, receiverHost: InetAddress) = "http://phone/m/1"
                override fun endSession() = Unit
            },
            local = local,
            prompt = PairingPrompt { _, _ -> null },
            scope = scope,
            connect = { error("must reuse the aimed connection") },
        )
        val media = SharedMedia(object : MediaFile {
            override val size = 1L
            override val mime = "video/mp4"
            override fun open(offset: Long) = ByteArrayInputStream(ByteArray(1))
        }, MediaKind.VIDEO, "clip", 60_000)

        assertTrue(coordinator.sendTo(Target(target.id, target.name, target.webSocketUrl, InetAddress.getLoopbackAddress()), media, conn))

        assertEquals(30_000, desk.lastHandoff!!.positionMs)
        assertEquals(1, desk.received.count { it is Hello })
        assertFalse(desk.glowing)
    }

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
}
