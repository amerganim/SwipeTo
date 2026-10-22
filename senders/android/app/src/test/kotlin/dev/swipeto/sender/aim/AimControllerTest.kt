package dev.swipeto.sender.aim

import dev.swipeto.protocol.AimStart
import dev.swipeto.protocol.Hello
import dev.swipeto.protocol.MediaInfo
import dev.swipeto.protocol.Prefetch
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
    private var cameraDelayMs = 0L
    private val camera = CameraMarkerResolver(MarkerScanner { _, accept ->
        delay(cameraDelayMs)
        seen?.takeIf(accept)
    }, timeoutMs = { 2_000 })

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

    private fun controller(screens: List<Pair<LinkTarget, Long?>>, facing: Facing = Facing.TOWARD) =
        options(screens.map { (t, used) -> AimOption(t, used, facing) })

    private fun options(options: List<AimOption>) =
        AimController(scope, links, { options }, preferred = camera, fallback = FallbackResolver, graceMs = 300)

    private suspend fun AimController.settled(): AimState = withTimeout(5_000) {
        state.first { it is AimState.Targeted || it is AimState.NoScreens }
    }

    @Test
    fun `the target gets a prefetch of what a swipe would send, a screen that only glows does not`() = runBlocking<Unit> {
        val clip = MediaInfo(MediaKind.VIDEO, "http://phone/m/s/clip", "video/mp4", "Clip", 180_000)
        var ended = 0
        val aim = AimController(
            scope, links, { listOf(AimOption(deskTarget, null, Facing.TOWARD), AimOption(tvTarget, null, Facing.UNKNOWN)) },
            preferred = camera, graceMs = 300,
            prefetch = { target -> Prefetch(clip, if (target.id == deskTarget.id) 42_000 else 1) },
            onEnded = { ended++ },
        )

        aim.onAimStart()
        assertEquals("Desk", (aim.settled() as AimState.Targeted).target.name)

        val prefetch = withTimeout(5_000) {
            var found: Prefetch? = null
            while (found == null) {
                found = desk.received.filterIsInstance<AimStart>().firstNotNullOfOrNull { it.prefetch }
                delay(20)
            }
            found
        }
        assertEquals(42_000, prefetch.positionMs)
        assertEquals(clip.url, prefetch.media.url)
        assertTrue(tv.received.filterIsInstance<AimStart>().none { it.prefetch != null })

        aim.onAimEnd()
        withTimeout(2_000) { aim.state.first { it is AimState.Idle } }
        assertEquals(1, ended) // the caller withdraws what it published for the prefetch
    }

    @Test
    fun `a swipe taking the target does not count as an ended aim`() = runBlocking<Unit> {
        var ended = 0
        val aim = AimController(scope, links, { listOf(AimOption(deskTarget, null, Facing.TOWARD)) }, preferred = camera, onEnded = { ended++ })

        aim.onAimStart()
        aim.settled()
        val (_, conn) = aim.takeTarget()!!
        conn.close()

        assertEquals(0, ended)
    }

    @Test
    fun `camera marker picks the screen in front, it locks, the other stops glowing`() = runBlocking<Unit> {
        seen = MarkerPayload(tvTarget.id, "TvMarkerNonce000001")
        val aim = controller(listOf(deskTarget to null, tvTarget to null), Facing.UNKNOWN)

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
    fun `stale marker still targets but is not verified`() = runBlocking<Unit> {
        seen = MarkerPayload(deskTarget.id, "PhotoOfAnOldMarker01")
        val aim = controller(listOf(deskTarget to null, tvTarget to null), Facing.UNKNOWN)

        aim.onAimStart()
        val targeted = aim.settled() as AimState.Targeted

        assertEquals("Desk", targeted.target.name)
        assertFalse(targeted.verified)
        assertFalse(desk.locked)
    }

    @Test
    fun `without a marker, the one screen the phone faces is the target`() = runBlocking<Unit> {
        val aim = controller(listOf(deskTarget to null))

        aim.onAimStart()
        val targeted = aim.settled() as AimState.Targeted

        assertEquals(ResolvedBy.COMPASS, targeted.by)
        withTimeout(2_000) { while (!desk.glowing) delay(20) }
    }

    @Test
    fun `without a marker and with several screens, the most recently used wins`() = runBlocking<Unit> {
        val aim = controller(listOf(deskTarget to 1_000L, tvTarget to 5_000L))

        aim.onAimStart()

        assertEquals("TV", (aim.settled() as AimState.Targeted).target.name)
    }

    @Test
    fun `lowering the phone ends the aim everywhere`() = runBlocking<Unit> {
        val aim = controller(listOf(deskTarget to null))
        aim.onAimStart()
        aim.settled()

        aim.onAimEnd()

        withTimeout(5_000) { while (desk.glowing) delay(20) }
        withTimeout(1_000) { aim.state.first { it == AimState.Idle } }
    }

    @Test
    fun `a single screen is the target at once, and the camera upgrades it to verified`() = runBlocking<Unit> {
        cameraDelayMs = 800
        seen = MarkerPayload(deskTarget.id, "DeskMarkerNonce0001")
        val aim = controller(listOf(deskTarget to null))

        aim.onAimStart()
        val first = withTimeout(500) { aim.state.first { it is AimState.Targeted } } as AimState.Targeted
        assertEquals(ResolvedBy.COMPASS, first.by)
        assertFalse(first.verified)

        val upgraded = withTimeout(3_000) { aim.state.first { it is AimState.Targeted && it.verified } } as AimState.Targeted
        assertEquals(ResolvedBy.CAMERA, upgraded.by)
        assertTrue(desk.locked)
    }

    @Test
    fun `a swipe while still searching among several screens takes the most recent`() = runBlocking<Unit> {
        cameraDelayMs = 3_000 // the camera has not found anything yet
        val aim = controller(listOf(deskTarget to 1_000L, tvTarget to 5_000L))
        aim.onAimStart()
        withTimeout(2_000) { aim.state.first { it is AimState.Searching } }

        val (target, conn) = aim.takeTarget()!!

        assertEquals("TV", target.name)
        assertTrue(!conn.closed.value)
        withTimeout(5_000) { while (desk.glowing) delay(20) }
    }

    @Test
    fun `a swipe just after lowering still gets the target`() = runBlocking<Unit> {
        val aim = controller(listOf(deskTarget to null))
        aim.onAimStart()
        aim.settled()

        aim.onAimEnd() // the swipe tipped the phone down
        val taken = aim.takeTarget()

        assertEquals("Desk", taken!!.first.name)
        delay(400) // past the grace period: still no aim_end, the handoff will hide the glow
        assertTrue(desk.received.none { it is dev.swipeto.protocol.AimEnd })
    }

    @Test
    fun `raising again within the grace period keeps the target`() = runBlocking<Unit> {
        val aim = controller(listOf(deskTarget to null))
        aim.onAimStart()
        aim.settled()

        aim.onAimEnd()
        delay(100)
        aim.onAimStart()
        delay(500)

        assertTrue(aim.state.value is AimState.Targeted)
        assertTrue(desk.glowing)
    }

    @Test
    fun `facing away from every learned screen, nothing glows`() = runBlocking<Unit> {
        val aim = controller(listOf(deskTarget to null, tvTarget to null), Facing.AWAY)

        aim.onAimStart()
        delay(600)

        assertEquals(AimState.Idle, aim.state.value)
        assertFalse(desk.glowing || tv.glowing)
        assertTrue(desk.received.none { it is dev.swipeto.protocol.AimStart })
    }

    @Test
    fun `a screen with no learned direction needs the camera`() = runBlocking<Unit> {
        // Nothing read by the camera: it glows (so its marker can be read) but is never the target.
        val aim = controller(listOf(deskTarget to null), Facing.UNKNOWN)

        aim.onAimStart()
        withTimeout(2_000) { aim.state.first { it is AimState.Searching } }
        delay(2_800) // the camera search times out

        assertTrue(aim.state.value is AimState.Searching)
        assertEquals(null, aim.takeTarget())
        withTimeout(2_000) { while (!desk.glowing) delay(20) }
    }

    @Test
    fun `facing one screen while another is unknown, the faced one is the target`() = runBlocking<Unit> {
        val aim = options(listOf(AimOption(deskTarget, null, Facing.TOWARD), AimOption(tvTarget, null, Facing.UNKNOWN)))

        aim.onAimStart()
        val targeted = aim.settled() as AimState.Targeted

        assertEquals("Desk", targeted.target.name)
        assertEquals(ResolvedBy.COMPASS, targeted.by)
    }

    @Test
    fun `no paired screen reachable`() = runBlocking<Unit> {
        val offline = LinkTarget(deskTarget.id, "Desk", "ws://127.0.0.1:1/v1")
        val aim = controller(listOf(offline to null))

        aim.onAimStart()

        assertEquals(AimState.NoScreens, aim.settled())
    }

    @Test
    fun `swipe-up hands off over the aimed connection without a second hello`() = runBlocking<Unit> {
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
