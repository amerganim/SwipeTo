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
        assertNull(swipes.classify(dxDp = 150f, dyDp = -160f, durationMs = 200, velocityYDpPerSec = -1_300f), "45 degrees")
        assertNull(swipes.classify(dxDp = 0f, dyDp = -200f, durationMs = 200, velocityYDpPerSec = 900f), "flicked back down at the end")
    }

    @Test
    fun `high sensitivity accepts a gentler swipe that normal rejects`() {
        val gentle = Triple(0f, -95f, 250L)
        assertNull(swipes.classify(gentle.first, gentle.second, gentle.third, -650f))
        assertEquals(Swipe.UP, SwipeClassifier(SwipeConfig.forSensitivity(Sensitivity.HIGH)).classify(gentle.first, gentle.second, gentle.third, -650f))
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
