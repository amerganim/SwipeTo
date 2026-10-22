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
        if (abs(velocityYDpPerSec) < c.minVelocityDpPerSec) return null
        // Angle away from the vertical axis: 0 = straight up/down, 90 = sideways (scrubbing).
        val angle = Math.toDegrees(atan2(abs(dxDp).toDouble(), abs(dyDp).toDouble()))
        if (angle > c.maxAngleFromVerticalDeg) return null
        if (hypot(dxDp, dyDp) == 0f) return null
        // Direction must agree between where the finger went and how it was moving at the end.
        return when {
            dyDp < 0 && velocityYDpPerSec < 0 -> Swipe.UP
            dyDp > 0 && velocityYDpPerSec > 0 -> Swipe.DOWN
            else -> null
        }
    }
}
