package dev.swipeto.sender.apps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Which app is in front, from Android's usage events ("Usage access", granted by the user). Gallery
 * tells no other app which photo it shows, so knowing Gallery is open is what lets a raise offer the
 * newest photos. Only the package name is read; no screen content.
 */
class ForegroundApp(private val context: Context) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private var last: String? = null
    private var checkedUntil = 0L

    val hasAccess: Boolean
        get() {
            val ops = context.getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION")
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }

    /** The package of the activity most recently brought to the front, or null without access. */
    fun current(nowMs: Long = System.currentTimeMillis()): String? {
        if (!hasAccess) return null
        val from = if (checkedUntil == 0L) nowMs - LOOKBACK_MS else checkedUntil
        val events = usage.queryEvents(from, nowMs)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
        }
        checkedUntil = nowMs
        return last
    }

    companion object {
        private const val LOOKBACK_MS = 10 * 60_000L

        /** Browsers: what they show is named only by a share (Share → Laptop). */
        val BROWSERS = setOf(
            "com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.microsoft.emmx",
            "com.opera.browser", "com.brave.browser", "com.duckduckgo.mobile.android",
        )

        /** Apps where you look at the phone's own photos and videos. */
        val GALLERIES = setOf("com.sec.android.gallery3d", "com.google.android.apps.photos", "com.android.gallery3d", "com.samsung.android.video")
    }
}
