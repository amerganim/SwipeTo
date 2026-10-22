package dev.swipeto.sender.aim

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Compass maths in degrees, 0 = north, clockwise, in [0, 360). */
object Compass {
    fun normalize(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    /** Smallest absolute difference between two headings, 0..180. */
    fun difference(a: Float, b: Float): Float {
        val d = abs(normalize(a) - normalize(b))
        return if (d > 180f) 360f - d else d
    }

    /**
     * Moves [current] towards [sample] by [weight] (0..1) along the circle, so 350 and 10 average to
     * 0, not 180.
     */
    fun blend(current: Float, sample: Float, weight: Float): Float {
        val x = (1 - weight) * cos(Math.toRadians(current.toDouble())) + weight * cos(Math.toRadians(sample.toDouble()))
        val y = (1 - weight) * sin(Math.toRadians(current.toDouble())) + weight * sin(Math.toRadians(sample.toDouble()))
        return normalize(Math.toDegrees(atan2(y, x)).toFloat())
    }
}

/** Where a paired screen is, as learned from this phone's compass. */
data class ScreenBearing(val headingDeg: Float, val samples: Int)

/** How a screen relates to where the phone is facing right now. */
enum class Facing {
    /** Its learned direction is within the tolerance: a target without the camera. */
    TOWARD,

    /** No direction learned yet: it may glow, but only the camera can make it the target. */
    UNKNOWN,

    /** Learned, and the phone faces elsewhere: leave it alone. */
    AWAY,
}

/**
 * Decides which paired screens a raise is aimed at, from the phone's compass heading and each
 * screen's learned bearing. Without a heading (no magnetometer) every screen is [Facing.UNKNOWN]
 * when nothing is learned, and [Facing.TOWARD] otherwise, so phones without a compass keep working.
 */
class PointingFilter(private val toleranceDeg: Float = 35f) {
    fun facing(headingDeg: Float?, bearing: ScreenBearing?): Facing = when {
        bearing == null -> Facing.UNKNOWN
        headingDeg == null -> Facing.TOWARD
        Compass.difference(headingDeg, bearing.headingDeg) <= toleranceDeg -> Facing.TOWARD
        else -> Facing.AWAY
    }

    /** Folds one more observation into a screen's bearing; camera locks count more than handoffs. */
    fun learn(previous: ScreenBearing?, headingDeg: Float, fromCamera: Boolean): ScreenBearing {
        if (previous == null) return ScreenBearing(Compass.normalize(headingDeg), 1)
        // Early samples move it a lot, later ones only nudge (indoor compasses wander).
        val weight = (if (fromCamera) 0.5f else 0.25f).coerceAtLeast(1f / (previous.samples + 1)).coerceAtMost(0.5f)
        return ScreenBearing(Compass.blend(previous.headingDeg, headingDeg, weight), previous.samples + 1)
    }
}
