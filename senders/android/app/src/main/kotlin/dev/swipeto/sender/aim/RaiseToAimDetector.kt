package dev.swipeto.sender.aim

/** One orientation reading: camera elevation in degrees above the horizon (see [AimConfig]). */
data class OrientationSample(val timeMs: Long, val elevationDeg: Float)

enum class AimPhase { IDLE, AIMING }

/**
 * Raise-to-aim: a deliberate lift of the phone from however it was held towards a screen, then a
 * moment of steadiness. Pure logic over [OrientationSample]s so it can be tested with recorded or
 * synthetic traces; [OrientationSensor] feeds it on the phone.
 *
 * The rise is measured **relative** to where the phone just was, not as an absolute angle: people
 * watch with the phone anywhere from -40 to -75 degrees, and a laptop on a desk is below eye level,
 * so "pointing at the screen" can be as low as -35 (measured on a Galaxy A15, see AIM_TUNING.md).
 *
 * Enter AIMING when, at once:
 * - the elevation is inside the band [AimConfig.bandMinDeg]..[AimConfig.bandMaxDeg],
 * - it is at least [AimConfig.minRiseDeg] above the lowest elevation of the last
 *   [AimConfig.raiseWindowMs] (a quick lift, so slow drift and simply holding still never trigger),
 * - and it has stayed so, within [AimConfig.steadyJitterDeg], for [AimConfig.dwellMs].
 *
 * Leave AIMING after [AimConfig.exitDwellMs] more than [AimConfig.exitDropDeg] below the highest
 * point of this aim, below [AimConfig.exitBelowDeg], or above [AimConfig.exitAboveDeg].
 */
class RaiseToAimDetector(var config: AimConfig = AimConfig()) {
    var phase = AimPhase.IDLE
        private set

    private val recent = ArrayDeque<OrientationSample>()
    private var steadySince: Long? = null
    private var steadyMin = 0f
    private var steadyMax = 0f
    private var aimPeak = 0f
    private var outsideSince: Long? = null

    /** Feeds one sample; returns the new phase if it changed, null otherwise. */
    fun onSample(sample: OrientationSample): AimPhase? {
        recent.addLast(sample)
        while (recent.isNotEmpty() && sample.timeMs - recent.first().timeMs > config.raiseWindowMs) recent.removeFirst()
        return when (phase) {
            AimPhase.IDLE -> idle(sample.timeMs, sample.elevationDeg)
            AimPhase.AIMING -> aiming(sample.timeMs, sample.elevationDeg)
        }
    }

    /** Forget history, e.g. after a handoff or when the screen turns off. */
    fun reset() {
        phase = AimPhase.IDLE
        recent.clear()
        steadySince = null
        outsideSince = null
    }

    private fun idle(t: Long, e: Float): AimPhase? {
        val c = config
        val low = recent.minOf { it.elevationDeg }
        if (e < c.bandMinDeg || e > c.bandMaxDeg || e - low < c.minRiseDeg) {
            steadySince = null
            return null
        }
        val since = steadySince
        if (since == null) {
            steadySince = t
            steadyMin = e
            steadyMax = e
            return null
        }
        steadyMin = minOf(steadyMin, e)
        steadyMax = maxOf(steadyMax, e)
        if (steadyMax - steadyMin > c.steadyJitterDeg) {
            // Still swinging: restart the steadiness clock from here.
            steadySince = t
            steadyMin = e
            steadyMax = e
            return null
        }
        if (t - since < c.dwellMs) return null
        phase = AimPhase.AIMING
        aimPeak = e
        outsideSince = null
        return AimPhase.AIMING
    }

    private fun aiming(t: Long, e: Float): AimPhase? {
        val c = config
        val out = e < c.exitBelowDeg || e > c.exitAboveDeg || e < aimPeak - c.exitDropDeg
        if (!out) {
            aimPeak = maxOf(aimPeak, e)
            outsideSince = null
            return null
        }
        val since = outsideSince ?: t.also { outsideSince = it }
        if (t - since < c.exitDwellMs) return null
        phase = AimPhase.IDLE
        steadySince = null
        // The lowered pose is the new baseline; the next raise is measured from here.
        recent.clear()
        recent.addLast(OrientationSample(t, e))
        return AimPhase.IDLE
    }
}
