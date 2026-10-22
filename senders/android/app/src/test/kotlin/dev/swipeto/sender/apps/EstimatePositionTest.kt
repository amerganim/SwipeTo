package dev.swipeto.sender.apps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EstimatePositionTest {
    @Test
    fun `a playing session moves on from its last update at its speed`() {
        assertEquals(42_000 + 3_000, estimatePosition(42_000, updatedAtMs = 10_000, speed = 1f, playing = true, nowMs = 13_000, durationMs = null))
        assertEquals(42_000 + 6_000, estimatePosition(42_000, updatedAtMs = 10_000, speed = 2f, playing = true, nowMs = 13_000, durationMs = null))
    }

    @Test
    fun `a paused session stays, and nothing runs past the end or before an update`() {
        assertEquals(42_000, estimatePosition(42_000, updatedAtMs = 10_000, speed = 1f, playing = false, nowMs = 60_000, durationMs = null))
        assertEquals(180_000, estimatePosition(179_000, updatedAtMs = 0, speed = 1f, playing = true, nowMs = 5_000, durationMs = 180_000))
        assertEquals(42_000, estimatePosition(42_000, updatedAtMs = 10_000, speed = 1f, playing = true, nowMs = 9_000, durationMs = null))
    }
}
