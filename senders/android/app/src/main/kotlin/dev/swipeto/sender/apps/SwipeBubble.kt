package dev.swipeto.sender.apps

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.swipeto.sender.aim.Swipe
import dev.swipeto.sender.aim.SwipeClassifier

/** What the bubble over another app shows. */
sealed interface BubbleState {
    data object Hidden : BubbleState

    /** Raised toward a screen (or still finding it): swipe up on the bubble sends. */
    data class Aiming(val screen: String?, val locked: Boolean) : BubbleState

    /**
     * Raised in Gallery, which does not say what it shows: the newest photos and videos to tap
     * ([thumbs] in the same order; null where no thumbnail could be made). Swipe up sends the first.
     */
    data class Pick(val screen: String, val thumbs: List<Bitmap?>, val videos: Set<Int>) : BubbleState

    /** Raised toward [screen] while an app plays something only a share can name: how to send it. */
    data class Hint(val screen: String, val text: String) : BubbleState

    /** The screen has it: swipe down brings it back, left/right pages an [album], a tap opens the remote. */
    data class OnScreen(val screen: String, val album: Boolean = false) : BubbleState
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
    private val onPick: (Int) -> Unit = {},
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var root: LinearLayout? = null
    private var pill: TextView? = null
    private var strip: LinearLayout? = null
    private var state: BubbleState = BubbleState.Hidden

    val canShow: Boolean get() = Settings.canDrawOverlays(context)

    val showing: BubbleState get() = state

    fun show(next: BubbleState) {
        if (next == state) return
        state = next
        if (next is BubbleState.Hidden || !canShow) {
            remove()
            return
        }
        if (root == null) create()
        val (text, accent) = when (next) {
            is BubbleState.Aiming -> (next.screen?.let { "↑  Swipe up to send to $it" } ?: "Looking for the screen…") to next.locked
            is BubbleState.Pick -> "Tap one to send to ${next.screen}" to true
            is BubbleState.Hint -> next.text to false
            is BubbleState.OnScreen -> (if (next.album) "On ${next.screen}  ·  ← → more  ·  ↓ bring back" else "On ${next.screen}  ·  swipe down to bring back") to false
            BubbleState.Hidden -> "" to false
        }
        pill!!.text = text
        (pill!!.background as GradientDrawable).setStroke(dp(3), if (accent) ACCENT else Color.TRANSPARENT)
        fillStrip((next as? BubbleState.Pick))
    }

    fun remove() {
        root?.let { runCatching { windows.removeView(it) } }
        root = null
        pill = null
        strip = null
        state = BubbleState.Hidden
    }

    private fun fillStrip(pick: BubbleState.Pick?) {
        val row = strip ?: return
        row.removeAllViews()
        (row.parent as HorizontalScrollView).visibility = if (pick == null) android.view.View.GONE else android.view.View.VISIBLE
        pick ?: return
        pick.thumbs.forEachIndexed { index, bitmap ->
            row.addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (bitmap != null) setImageBitmap(bitmap) else setBackgroundColor(Color.DKGRAY)
                    contentDescription = if (index in pick.videos) "Video ${index + 1}" else "Photo ${index + 1}"
                    setOnClickListener { onPick(index) }
                    if (index in pick.videos) foreground = context.getDrawable(android.R.drawable.ic_media_play)
                },
                LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginEnd = dp(6) },
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create() {
        val label = TextView(context).apply {
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
        label.setOnTouchListener { _, e ->
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
                    val vx = (tracker?.xVelocity ?: 0f) / density
                    val vy = (tracker?.yVelocity ?: 0f) / density
                    tracker?.recycle()
                    tracker = null
                    val dx = (e.rawX - downX) / density
                    val dy = (e.rawY - downY) / density
                    val duration = e.eventTime - downAt
                    val swipe = classifier.classify(dx, dy, duration, vy) ?: classifier.classifyHorizontal(dx, dy, duration, vx)
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
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(225, 20, 20, 24))
            }
            addView(row)
            visibility = android.view.View.GONE
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(scroller, LinearLayout.LayoutParams(dp(340), LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            addView(label)
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
        windows.addView(column, params)
        root = column
        pill = label
        strip = row
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        val ACCENT = Color.rgb(0x00, 0xB7, 0xFF) // ui.Accent
    }
}
