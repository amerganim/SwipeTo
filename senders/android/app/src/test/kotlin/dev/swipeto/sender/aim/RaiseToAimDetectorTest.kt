package dev.swipeto.sender.aim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Synthetic sensor traces at 50 Hz, the rate the phone delivers SENSOR_DELAY_GAME. */
class RaiseToAimDetectorTest {
    private val step = 20L
    private var t = 0L
    private val detector = RaiseToAimDetector()
    private val changes = mutableListOf<Pair<Long, AimPhase>>()

    private fun hold(elevation: Float, ms: Long, noise: Float = 0f, random: Random = Random(1)) {
        val end = t + ms
        while (t < end) {
            feed(elevation + if (noise > 0) random.nextFloat() * 2 * noise - noise else 0f)
        }
    }

    private fun ramp(from: Float, to: Float, ms: Long) {
        val n = (ms / step).toInt()
        for (i in 0 until n) feed(from + (to - from) * i / n)
    }

    private fun feed(elevation: Float) {
        detector.onSample(OrientationSample(t, elevation))?.let { changes += t to it }
        t += step
    }

    @Test
    fun `raise from viewing pose to pointing aims within 300 ms of reaching the band`() {
        hold(-50f, 1_000, noise = 3f)
        ramp(-50f, 5f, 400)
        val reachedBandAt = t
        hold(5f, 600, noise = 2f)

        val (at, phase) = changes.single()
        assertEquals(AimPhase.AIMING, phase)
        assertTrue(at - reachedBandAt <= 300, "aimed ${at - reachedBandAt} ms after reaching the band")
    }

    @Test
    fun `holding the phone upright without raising it never aims`() {
        hold(0f, 5_000, noise = 3f)

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `a slow drift up is not a raise`() {
        hold(-50f, 500)
        ramp(-50f, 0f, 4_000)
        hold(0f, 1_000)

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `swinging through the band to the ceiling does not aim`() {
        hold(-50f, 500)
        ramp(-50f, 85f, 300)
        hold(85f, 1_000)

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `lowering the phone ends the aim, a brief dip does not`() {
        hold(-50f, 500)
        ramp(-50f, 5f, 400)
        hold(5f, 500)
        assertEquals(AimPhase.AIMING, detector.phase)

        hold(-55f, 100) // a wobble
        hold(5f, 300)
        assertEquals(AimPhase.AIMING, detector.phase)

        hold(-55f, 400) // put down to watch again
        assertEquals(AimPhase.IDLE, detector.phase)
        assertEquals(listOf(AimPhase.AIMING, AimPhase.IDLE), changes.map { it.second })
    }

    @Test
    fun `can aim again after lowering`() {
        repeat(3) {
            hold(-50f, 500)
            ramp(-50f, 5f, 400)
            hold(5f, 400)
            hold(-55f, 500)
        }

        assertEquals(6, changes.size)
    }

    @Test
    fun `casual handling while watching does not trigger`() {
        // Twenty minutes of watching: a slow wander between -75 and -40 (as recorded on the A15) plus
        // hand tremor of a couple of degrees, sample to sample.
        val random = Random(42)
        var pose = -55f
        repeat(60_000) {
            pose = (pose + random.nextFloat() * 0.8f - 0.4f).coerceIn(-75f, -40f)
            feed(pose + random.nextFloat() * 4 - 2)
        }

        assertTrue(changes.isEmpty(), "false triggers: $changes")
    }

    @Test
    fun `raising toward a laptop on a desk aims even though the camera still points down`() {
        hold(-60f, 1_000, noise = 3f)
        ramp(-60f, -32f, 350)
        hold(-32f, 500, noise = 2f)

        assertEquals(listOf(AimPhase.AIMING), changes.map { it.second })
    }

    @Test
    fun `recorded A15 trace - five raises toward a laptop, four held long enough, no false triggers`() {
        // Real data: the A15 test phone, filtered accelerometer, a person watching then raising to a laptop.
        val trace = javaClass.getResource("/a15-raises.csv")!!.readText().lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split(',').let { OrientationSample(it[0].toLong(), it[1].toFloat()) } }
            .toList()
        val aims = trace.mapNotNull { sample -> detector.onSample(sample)?.let { sample to it } }
            .filter { it.second == AimPhase.AIMING }

        // The fifth raise peaked for under 100 ms and dropped straight back: not an aim.
        assertEquals(4, aims.size, "aims at ${aims.map { it.first }}")
        assertTrue(aims.all { it.first.elevationDeg in -40f..-20f })
    }

    @Test
    fun `sensitivity presets differ the way they say`() {
        val low = AimConfig.forSensitivity(Sensitivity.LOW)
        val high = AimConfig.forSensitivity(Sensitivity.HIGH)

        assertTrue(low.dwellMs > AimConfig().dwellMs && high.dwellMs < AimConfig().dwellMs)
        assertTrue(low.minRiseDeg > high.minRiseDeg)
    }

    @Test
    fun `reset forgets a pending raise`() {
        hold(-50f, 500)
        ramp(-50f, 5f, 400)
        detector.reset()
        // The raise happened before the reset, so holding still now must not aim.
        hold(5f, 50)
        assertNull(changes.lastOrNull())
        assertNotNull(detector.phase)
    }
}
