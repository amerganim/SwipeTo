package dev.swipeto.sender.aim

/** How eagerly raise-to-aim and swipes trigger. Settings → Sensitivity. */
enum class Sensitivity { LOW, NORMAL, HIGH }

/**
 * Every gesture and aim threshold in one place (see senders/android/AIM_TUNING.md for what each one
 * does and how to tune it on a phone).
 *
 * Angles are the **camera elevation**: where the back camera points, in degrees above the horizon.
 * Lying flat face up = -90, held upright = 0, pointing at the ceiling = +90. Measured on the A15 test phone:
 * watching a video, -45 to -75; pointing at a laptop on a desk, -27 to -37 (a wall TV is nearer 0).
 * So aiming is a *rise* relative to the moment before, not a fixed angle.
 */
data class AimConfig(
    /** Aiming = elevation inside this band... (low enough for a laptop on a desk, measured at -27 to -37) */
    val bandMinDeg: Float = -45f,
    val bandMaxDeg: Float = 40f,
    /** ...at least this far above the lowest point of the last [raiseWindowMs] (a quick, deliberate lift)... */
    val minRiseDeg: Float = 18f,
    val raiseWindowMs: Long = 1_200,
    /** ...and held steady for this long, moving less than [steadyJitterDeg]. */
    val dwellMs: Long = 120,
    val steadyJitterDeg: Float = 10f,
    /** Aim ends after [exitDwellMs] more than [exitDropDeg] below its highest point, below [exitBelowDeg] or above [exitAboveDeg]. */
    val exitDropDeg: Float = 18f,
    val exitBelowDeg: Float = -60f,
    val exitAboveDeg: Float = 65f,
    val exitDwellMs: Long = 250,
    /** Camera marker search gives up after this and the fallback resolver chooses. */
    val markerSearchMs: Long = 1_500,
) {
    companion object {
        fun forSensitivity(sensitivity: Sensitivity) = when (sensitivity) {
            Sensitivity.LOW -> AimConfig(minRiseDeg = 24f, dwellMs = 180, steadyJitterDeg = 8f)
            Sensitivity.NORMAL -> AimConfig()
            Sensitivity.HIGH -> AimConfig(bandMinDeg = -50f, minRiseDeg = 13f, raiseWindowMs = 1_500, dwellMs = 90, steadyJitterDeg = 13f)
        }
    }
}

/** Swipe thresholds, in dp and dp/s, measured on the video surface. */
data class SwipeConfig(
    // Tuned on the A15 test phone: natural thumb swipes arc 30-50 degrees off vertical and are often under
    // 100 dp. Loose is safe here, because swipe-up only acts while the phone is aimed at a screen.
    val minDistanceDp: Float = 70f,
    val minVelocityDpPerSec: Float = 650f,
    /** How far from straight up/down the swipe may lean. */
    val maxAngleFromVerticalDeg: Float = 40f,
    val maxDurationMs: Long = 700,
) {
    companion object {
        fun forSensitivity(sensitivity: Sensitivity) = when (sensitivity) {
            Sensitivity.LOW -> SwipeConfig(minDistanceDp = 110f, minVelocityDpPerSec = 900f, maxAngleFromVerticalDeg = 30f)
            Sensitivity.NORMAL -> SwipeConfig()
            Sensitivity.HIGH -> SwipeConfig(minDistanceDp = 50f, minVelocityDpPerSec = 450f, maxAngleFromVerticalDeg = 50f, maxDurationMs = 900)
        }
    }
}
