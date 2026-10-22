package dev.swipeto.sender.apps

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.WindowManager
import android.widget.TextView
import dev.swipeto.sender.aim.Swipe
import dev.swipeto.sender.aim.SwipeClassifier

/** What the bubble over another app shows. */
sealed interface BubbleState {
    data object Hidden : BubbleState

    /** Raised toward a screen (or still finding it): swipe up on the bubble sends. */
    data class Aiming(val screen: String?, val locked: Boolean) : BubbleState

    /** The screen has it: swipe down on the bubble brings it back, a tap opens the remote. */
    data class OnScreen(val screen: String) : BubbleState
}

/**
 * A pill floating over whatever app is in front (needs "Display over other apps"). Other apps'
 * touches never reach SwipeTo, so this is where the swipe happens. Plain views, no Compose: it lives
 * in the service's window, outside any activity.
 */
class SwipeBubble(
    private val context: Context,
    private val classifier: SwipeClassifier,
    private val onSwipe: (Swipe) -> Unit,
    private val onTap: () -> Unit,
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var view: TextView? = null
    private var state: BubbleState = BubbleState.Hidden

    val canShow: Boolean get() = Settings.canDrawOverlays(context)

    fun show(next: BubbleState) {
        if (next == state) return
        state = next
        if (next is BubbleState.Hidden || !canShow) {
            remove()
            return
        }
        val pill = view ?: create().also { view = it }
        val (text, accent) = when (next) {
            is BubbleState.Aiming -> (next.screen?.let { "↑  Swipe up to send to $it" } ?: "Looking for the screen…") to next.locked
            is BubbleState.OnScreen -> "On ${next.screen}  ·  swipe down to bring back" to false
            BubbleState.Hidden -> "" to false
        }
        pill.text = text
        (pill.background as GradientDrawable).setStroke(dp(3), if (accent) ACCENT else Color.TRANSPARENT)
    }

    fun remove() {
        view?.let { runCatching { windows.removeView(it) } }
        view = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create(): TextView {
        val pill = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(22), dp(24), dp(22))
            minWidth = dp(280)
            background = GradientDrawable().apply {
                cornerRadius = dp(40).toFloat()
                setColor(Color.argb(225, 20, 20, 24))
            }
            contentDescription = "SwipeTo: swipe up to send to the screen"
        }
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var tracker: VelocityTracker? = null
        pill.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    downAt = e.eventTime
                    tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> tracker?.addMovement(e)
                MotionEvent.ACTION_UP -> {
                    tracker?.addMovement(e)
                    tracker?.computeCurrentVelocity(1000)
                    val vy = (tracker?.yVelocity ?: 0f) / density
                    tracker?.recycle()
                    tracker = null
                    val dx = (e.rawX - downX) / density
                    val dy = (e.rawY - downY) / density
                    val swipe = classifier.classify(dx, dy, e.eventTime - downAt, vy)
                    when {
                        swipe != null -> onSwipe(swipe)
                        kotlin.math.hypot(dx, dy) < 12f -> onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    tracker?.recycle()
                    tracker = null
                }
            }
            true
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never takes focus or touches outside itself: the app underneath keeps working.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(140)
        }
        windows.addView(pill, params)
        return pill
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        val ACCENT = Color.rgb(0x00, 0xB7, 0xFF) // ui.Accent
    }
}
