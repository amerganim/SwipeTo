package dev.swipeto.sender.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Aim, swipe and handoff events, to logcat (tag SwipeTo) and to `files/events.log` in app storage.
 * The file survives logcat rolling over and testing with the phone unplugged; read it with
 * `adb exec-out run-as dev.swipeto.sender cat files/events.log` (debug builds). Kept under ~1 MB.
 */
object EventLog {
    private const val TAG = "SwipeTo"
    private const val MAX_BYTES = 1_000_000L
    private val writer = Executors.newSingleThreadExecutor()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "events.log")
    }

    fun i(message: String) {
        Log.i(TAG, message)
        append(message)
    }

    fun d(message: String) {
        Log.d(TAG, message)
        append(message)
    }

    private fun append(message: String) {
        val target = file ?: return
        val line = "${time.format(Date())} $message\n"
        writer.execute {
            runCatching {
                if (target.length() > MAX_BYTES) target.renameTo(File(target.parentFile, "events.old.log"))
                target.appendText(line)
            }
        }
    }
}
