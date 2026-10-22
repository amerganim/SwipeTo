package dev.swipeto.sender.aim

/** How eagerly raise-to-aim and swipes trigger. Settings → Sensitivity. */
enum class Sensitivity { LOW, NORMAL, HIGH }

/**
 * Every gesture and aim threshold in one place (see senders/android/AIM_TUNING.md for what each one
 * does and how to tune it on a phone).
 *
 * Angles are the **camera elevation**: where the back camera points, in degrees above the horizon.
 * Lying flat face up = -90, held upright = 0, pointing at the ceiling = +90. Watching a video the
 * camera points well below the horizon (about -30 to -60); aiming at a screen it is near level.
 */
data class AimConfig(
    /** Aiming = elevation inside this band... */
    val bandMinDeg: Float = -20f,
    val bandMaxDeg: Float = 40f,
    /** ...reached from at or below this elevation (the "held for viewing" pose)... */
    val raisedFromBelowDeg: Float = -35f,
    /** ...within this long before entering the band (a deliberate raise, not a slow drift)... */
    val raiseWindowMs: Long = 900,
    /** ...and held steady for this long, moving less than [steadyJitterDeg]. */
    val dwellMs: Long = 120,
    val steadyJitterDeg: Float = 10f,
    /** Aim ends when the phone goes back below this, or above [exitAboveDeg], for [exitDwellMs]. */
    val exitBelowDeg: Float = -40f,
    val exitAboveDeg: Float = 65f,
    val exitDwellMs: Long = 250,
    /** Camera marker search gives up after this and the fallback resolver chooses. */
    val markerSearchMs: Long = 1_500,
) {
    companion object {
        fun forSensitivity(sensitivity: Sensitivity) = when (sensitivity) {
            Sensitivity.LOW -> AimConfig(raisedFromBelowDeg = -45f, raiseWindowMs = 700, dwellMs = 200, steadyJitterDeg = 7f)
            Sensitivity.NORMAL -> AimConfig()
            Sensitivity.HIGH -> AimConfig(bandMinDeg = -25f, raisedFromBelowDeg = -28f, raiseWindowMs = 1_200, dwellMs = 80, steadyJitterDeg = 14f)
        }
    }
}

/** Swipe thresholds, in dp and dp/s, measured on the video surface. */
data class SwipeConfig(
    val minDistanceDp: Float = 110f,
    val minVelocityDpPerSec: Float = 800f,
    /** How far from straight up/down the swipe may lean. */
    val maxAngleFromVerticalDeg: Float = 30f,
    val maxDurationMs: Long = 700,
) {
    companion object {
        fun forSensitivity(sensitivity: Sensitivity) = when (sensitivity) {
            Sensitivity.LOW -> SwipeConfig(minDistanceDp = 150f, minVelocityDpPerSec = 1_100f, maxAngleFromVerticalDeg = 22f)
            Sensitivity.NORMAL -> SwipeConfig()
            Sensitivity.HIGH -> SwipeConfig(minDistanceDp = 80f, minVelocityDpPerSec = 550f, maxAngleFromVerticalDeg = 38f, maxDurationMs = 900)
        }
    }
}
