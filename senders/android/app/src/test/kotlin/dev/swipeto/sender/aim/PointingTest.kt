package dev.swipeto.sender.aim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PointingTest {
    private val filter = PointingFilter(toleranceDeg = 35f)

    @Test
    fun `compass difference wraps around north`() {
        assertEquals(20f, Compass.difference(350f, 10f), 0.01f)
        assertEquals(180f, Compass.difference(0f, 180f), 0.01f)
        assertEquals(5f, Compass.difference(-5f, 0f), 0.01f)
    }

    @Test
    fun `blending averages along the circle`() {
        assertEquals(0f, Compass.difference(Compass.blend(350f, 10f, 0.5f), 0f), 0.01f)
        assertEquals(90f, Compass.blend(80f, 100f, 0.5f), 0.01f)
    }

    @Test
    fun `facing a learned screen, facing away, and not learned yet`() {
        val laptop = ScreenBearing(120f, 3)

        assertEquals(Facing.TOWARD, filter.facing(140f, laptop))
        assertEquals(Facing.AWAY, filter.facing(200f, laptop)) // turned to the side: no glow
        assertEquals(Facing.UNKNOWN, filter.facing(200f, null)) // first time: camera decides
        assertEquals(Facing.TOWARD, filter.facing(null, laptop)) // no compass: fall back to learned screens
    }

    @Test
    fun `learning settles on the screen's direction and survives one stray reading`() {
        var b: ScreenBearing? = null
        listOf(118f, 124f, 121f, 119f).forEach { b = filter.learn(b, it, fromCamera = true) }
        assertTrue(Compass.difference(b!!.headingDeg, 120f) < 3f, "learned ${b!!.headingDeg}")

        b = filter.learn(b, 200f, fromCamera = false) // one bad compass moment
        assertTrue(Compass.difference(b!!.headingDeg, 120f) < 25f, "after stray ${b!!.headingDeg}")
        assertEquals(Facing.TOWARD, filter.facing(125f, b))
    }

    @Test
    fun `first sample is taken as is`() {
        assertEquals(ScreenBearing(10f, 1), filter.learn(null, 370f, fromCamera = true))
    }
}
