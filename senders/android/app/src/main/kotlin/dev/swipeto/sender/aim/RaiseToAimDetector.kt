package dev.swipeto.sender.aim

/** One orientation reading: camera elevation in degrees above the horizon (see [AimConfig]). */
data class OrientationSample(val timeMs: Long, val elevationDeg: Float)

enum class AimPhase { IDLE, AIMING }

/**
 * Raise-to-aim: from "held for viewing" (camera pointing down) to "raised and pointing forward"
 * (camera near level), deliberately and then steady. Pure logic over [OrientationSample]s so it can
 * be tested with recorded or synthetic traces; [OrientationSensor] feeds it on the phone.
 *
 * Enter AIMING when, at once:
 * - the elevation is inside the band [AimConfig.bandMinDeg]..[AimConfig.bandMaxDeg],
 * - it was at or below [AimConfig.raisedFromBelowDeg] within [AimConfig.raiseWindowMs] before
 *   entering the band (a raise, so simply holding the phone upright never triggers),
 * - and it has stayed in the band, within [AimConfig.steadyJitterDeg], for [AimConfig.dwellMs].
 *
 * Leave AIMING after [AimConfig.exitDwellMs] below [AimConfig.exitBelowDeg] or above
 * [AimConfig.exitAboveDeg] (hysteresis: small wobbles while aiming don't end it).
 */
class RaiseToAimDetector(var config: AimConfig = AimConfig()) {
    var phase = AimPhase.IDLE
        private set

    private var lastLowAt = Long.MIN_VALUE / 2
    private var bandEnteredAt: Long? = null
    private var raisedForThisEntry = false
    private var bandMin = 0f
    private var bandMax = 0f
    private var outsideSince: Long? = null

    /** Feeds one sample; returns the new phase if it changed, null otherwise. */
    fun onSample(sample: OrientationSample): AimPhase? {
        val e = sample.elevationDeg
        val t = sample.timeMs
        return when (phase) {
            AimPhase.IDLE -> idle(t, e)
            AimPhase.AIMING -> aiming(t, e)
        }
    }

    /** Forget history, e.g. after a handoff or when the screen turns off. */
    fun reset() {
        phase = AimPhase.IDLE
        lastLowAt = Long.MIN_VALUE / 2
        bandEnteredAt = null
        outsideSince = null
    }

    private fun idle(t: Long, e: Float): AimPhase? {
        val c = config
        if (e <= c.raisedFromBelowDeg) lastLowAt = t
        if (e < c.bandMinDeg || e > c.bandMaxDeg) {
            bandEnteredAt = null
            return null
        }
        val entered = bandEnteredAt
        if (entered == null) {
            bandEnteredAt = t
            raisedForThisEntry = t - lastLowAt <= c.raiseWindowMs
            bandMin = e
            bandMax = e
            return null
        }
        bandMin = minOf(bandMin, e)
        bandMax = maxOf(bandMax, e)
        if (bandMax - bandMin > c.steadyJitterDeg) {
            // Still swinging: restart the steadiness clock from here, keeping whether it was a raise.
            bandEnteredAt = t
            bandMin = e
            bandMax = e
            return null
        }
        if (raisedForThisEntry && t - entered >= c.dwellMs) {
            phase = AimPhase.AIMING
            outsideSince = null
            return AimPhase.AIMING
        }
        return null
    }

    private fun aiming(t: Long, e: Float): AimPhase? {
        val c = config
        if (e >= c.exitBelowDeg && e <= c.exitAboveDeg) {
            outsideSince = null
            return null
        }
        val since = outsideSince ?: t.also { outsideSince = it }
        if (t - since < c.exitDwellMs) return null
        phase = AimPhase.IDLE
        bandEnteredAt = null
        if (e <= c.raisedFromBelowDeg) lastLowAt = t
        return AimPhase.IDLE
    }
}
