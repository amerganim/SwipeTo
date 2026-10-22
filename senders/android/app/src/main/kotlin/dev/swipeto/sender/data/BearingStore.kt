package dev.swipeto.sender.data

import android.content.Context
import dev.swipeto.sender.aim.ScreenBearing

/** The compass bearing learned for each paired screen (see PointingFilter). */
class BearingStore(context: Context) {
    private val prefs = context.getSharedPreferences("swipeto_bearings", Context.MODE_PRIVATE)

    fun get(receiverId: String): ScreenBearing? {
        val samples = prefs.getInt(KEY_SAMPLES + receiverId, 0)
        if (samples == 0) return null
        return ScreenBearing(prefs.getFloat(KEY_HEADING + receiverId, 0f), samples)
    }

    fun put(receiverId: String, bearing: ScreenBearing) = prefs.edit()
        .putFloat(KEY_HEADING + receiverId, bearing.headingDeg)
        .putInt(KEY_SAMPLES + receiverId, bearing.samples)
        .apply()

    /** After moving a screen or the phone's case magnet confused the compass. */
    fun forgetAll() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_HEADING = "heading."
        const val KEY_SAMPLES = "samples."
    }
}
