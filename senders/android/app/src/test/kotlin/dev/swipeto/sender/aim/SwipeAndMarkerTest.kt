package dev.swipeto.sender.aim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SwipeAndMarkerTest {
    private val swipes = SwipeClassifier()

    @Test
    fun `a firm flick up or down is a swipe`() {
        assertEquals(Swipe.UP, swipes.classify(dxDp = 10f, dyDp = -180f, durationMs = 180, velocityYDpPerSec = -1_500f))
        assertEquals(Swipe.DOWN, swipes.classify(dxDp = -15f, dyDp = 200f, durationMs = 200, velocityYDpPerSec = 1_400f))
    }

    @Test
    fun `scrubbing, slow drags, short nudges and leaning swipes are not`() {
        assertNull(swipes.classify(dxDp = 300f, dyDp = -40f, durationMs = 300, velocityYDpPerSec = -200f), "sideways scrub")
        assertNull(swipes.classify(dxDp = 0f, dyDp = -200f, durationMs = 1_500, velocityYDpPerSec = -150f), "slow drag")
        assertNull(swipes.classify(dxDp = 0f, dyDp = -40f, durationMs = 60, velocityYDpPerSec = -1_200f), "short nudge")
        assertNull(swipes.classify(dxDp = 170f, dyDp = -160f, durationMs = 200, velocityYDpPerSec = -1_300f), "47 degrees")
        assertNull(swipes.classify(dxDp = 0f, dyDp = -200f, durationMs = 200, velocityYDpPerSec = 900f), "flicked back down at the end")
    }

    @Test
    fun `high sensitivity accepts a gentler swipe that normal rejects`() {
        val gentle = Triple(0f, -60f, 250L)
        assertNull(swipes.classify(gentle.first, gentle.second, gentle.third, -500f))
        assertEquals(Swipe.UP, SwipeClassifier(SwipeConfig.forSensitivity(Sensitivity.HIGH)).classify(gentle.first, gentle.second, gentle.third, -500f))
    }

    @Test
    fun `natural thumb swipes recorded on a Galaxy A15 count`() {
        // (dx, dy, ms, vy) from logcat while a person swiped up to send.
        assertEquals(Swipe.UP, swipes.classify(141f, -227f, 500, -3297f))
        assertEquals(Swipe.UP, swipes.classify(55f, -84f, 174, -1691f))
        assertEquals(Swipe.UP, swipes.classify(18f, -75f, 154, -1948f))
        assertEquals(Swipe.UP, swipes.classify(53f, -147f, 149, -1770f))
        // Still not: a sideways drag at 49 degrees, a tap, and a flick that ended going down.
        assertNull(swipes.classify(114f, -100f, 434, -2236f))
        assertNull(swipes.classify(0f, 0f, 87, 0f))
        assertNull(swipes.classify(11f, -44f, 135, 1817f))
    }

    @Test
    fun `quick swipes that pause before the finger lifts count (A15 acceptance run)`() {
        // Clear upward swipes whose release velocity was near zero: the finger stopped, then lifted.
        assertEquals(Swipe.UP, swipes.classify(80f, -134f, 108, 177f))
        assertEquals(Swipe.UP, swipes.classify(71f, -202f, 141, 110f))
        assertEquals(Swipe.UP, swipes.classify(89f, -168f, 208, -17f))
        assertEquals(Swipe.UP, swipes.classify(60f, -148f, 207, 62f))
        assertEquals(Swipe.UP, swipes.classify(67f, -144f, 132, -174f))
        assertEquals(Swipe.UP, swipes.classify(41f, -140f, 99, -587f))
        // But a short one whose finger turned back down at the end still does not.
        assertNull(swipes.classify(21f, -91f, 142, 674f))
    }

    @Test
    fun `marker payloads`() {
        assertEquals(
            MarkerPayload("0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21", "Qm9vVGhlU2NyZWVu"),
            MarkerPayload.parse("swipeto:0E1D2C3B-4A59-4687-9A8B-7C6D5E4F3A21:Qm9vVGhlU2NyZWVu"),
        )
        assertNull(MarkerPayload.parse("https://example.com"))
        assertNull(MarkerPayload.parse("swipeto:not-a-uuid:Qm9vVGhlU2NyZWVu"))
        assertNull(MarkerPayload.parse("swipeto:0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21:short"))
        assertNull(MarkerPayload.parse(null))
    }
}
