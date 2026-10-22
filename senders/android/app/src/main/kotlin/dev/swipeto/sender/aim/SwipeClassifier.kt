package dev.swipeto.sender.aim

import kotlin.math.abs
import kotlin.math.atan2

enum class Swipe { UP, DOWN, LEFT, RIGHT }

/**
 * Decides whether a finished drag was a deliberate swipe. Distances in dp, screen coordinates (y
 * grows downwards), so swiping up has a negative [dyDp]. Vertical swipes send and bring back;
 * horizontal ones page through an album.
 */
class SwipeClassifier(var config: SwipeConfig = SwipeConfig()) {
    fun classify(dxDp: Float, dyDp: Float, durationMs: Long, velocityYDpPerSec: Float): Swipe? =
        when (along(dyDp, dxDp, durationMs, velocityYDpPerSec)) {
            -1 -> Swipe.UP
            1 -> Swipe.DOWN
            else -> null
        }

    /** The same rules turned sideways: a swipe left (next photo) has a negative [dxDp]. */
    fun classifyHorizontal(dxDp: Float, dyDp: Float, durationMs: Long, velocityXDpPerSec: Float): Swipe? =
        when (along(dxDp, dyDp, durationMs, velocityXDpPerSec)) {
            -1 -> Swipe.LEFT
            1 -> Swipe.RIGHT
            else -> null
        }

    /** -1 or +1 for a clear swipe along the [main] axis, 0 otherwise. */
    private fun along(main: Float, cross: Float, durationMs: Long, releaseVelocity: Float): Int {
        val c = config
        if (durationMs > c.maxDurationMs) return 0
        if (abs(main) < c.minDistanceDp) return 0
        // Angle away from the main axis: 0 = straight along it, 90 = across (scrubbing, for vertical).
        val angle = Math.toDegrees(atan2(abs(cross).toDouble(), abs(main).toDouble()))
        if (angle > c.maxAngleFromVerticalDeg) return 0

        // Speed: the faster of the release velocity and the average over the whole swipe. People
        // often push up firmly and pause a moment before lifting (seen on the A15), which leaves the
        // release velocity near zero although the swipe was quick and clear.
        val direction = if (main < 0) -1 else 1
        val releaseAlong = releaseVelocity * direction
        val average = abs(main) / (durationMs.coerceAtLeast(1) / 1000f)
        if (maxOf(releaseAlong, average) < c.minVelocityDpPerSec) return 0
        // A finger that clearly turned back at the end changed its mind.
        if (releaseAlong < -c.minVelocityDpPerSec) return 0
        return direction
    }
}
