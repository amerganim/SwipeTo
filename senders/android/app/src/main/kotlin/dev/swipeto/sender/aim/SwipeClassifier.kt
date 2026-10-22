package dev.swipeto.sender.aim

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

enum class Swipe { UP, DOWN }

/**
 * Decides whether a finished drag was a deliberate vertical swipe. Distances in dp, screen
 * coordinates (y grows downwards), so swiping up has a negative [dyDp].
 */
class SwipeClassifier(var config: SwipeConfig = SwipeConfig()) {
    fun classify(dxDp: Float, dyDp: Float, durationMs: Long, velocityYDpPerSec: Float): Swipe? {
        val c = config
        if (durationMs > c.maxDurationMs) return null
        if (abs(dyDp) < c.minDistanceDp) return null
        // Angle away from the vertical axis: 0 = straight up/down, 90 = sideways (scrubbing).
        val angle = Math.toDegrees(atan2(abs(dxDp).toDouble(), abs(dyDp).toDouble()))
        if (angle > c.maxAngleFromVerticalDeg) return null
        if (hypot(dxDp, dyDp) == 0f) return null

        // Speed: the faster of the release velocity and the average over the whole swipe. People
        // often push up firmly and pause a moment before lifting (seen on the A15), which leaves the
        // release velocity near zero although the swipe was quick and clear.
        val direction = if (dyDp < 0) -1f else 1f
        val releaseAlong = velocityYDpPerSec * direction
        val average = abs(dyDp) / (durationMs.coerceAtLeast(1) / 1000f)
        if (maxOf(releaseAlong, average) < c.minVelocityDpPerSec) return null
        // A finger that clearly turned back at the end changed its mind.
        if (releaseAlong < -c.minVelocityDpPerSec) return null
        return if (dyDp < 0) Swipe.UP else Swipe.DOWN
    }
}
