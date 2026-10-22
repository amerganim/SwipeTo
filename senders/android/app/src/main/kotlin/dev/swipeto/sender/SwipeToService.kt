package dev.swipeto.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.swipeto.sender.aim.AimState
import dev.swipeto.sender.aim.Swipe
import dev.swipeto.sender.apps.BubbleState
import dev.swipeto.sender.apps.SwipeBubble
import dev.swipeto.sender.data.EventLog
import dev.swipeto.sender.handoff.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Swiping from other apps (Phase 5B). A foreground service, so Samsung does not freeze SwipeTo while
 * Gallery or YouTube is in front; it shows the swipe bubble over them when the phone is raised toward
 * a screen, and keeps a quiet notification with the state (and Bring back while a screen plays).
 * All the work is done by the app-wide [MainViewModel].
 */
class SwipeToService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine get() = (application as SwipeToApp).engine
    private lateinit var bubble: SwipeBubble
    private lateinit var notifications: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ready to send", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while SwipeTo can send what other apps play to a screen."
            },
        )
        startForegroundCompat(notification("Ready to send to a screen"))
        bubble = SwipeBubble(
            this,
            engine.swipes,
            onSwipe = { swipe ->
                scope.launch {
                    when (swipe) {
                        Swipe.UP -> engine.swipeUpFromAnywhere()
                        Swipe.DOWN -> if (engine.link.value is LinkState.Remote) engine.bringBack()
                        else -> Unit
                    }
                }
            },
            onTap = { if (engine.link.value is LinkState.Remote) openApp() },
        )
        scope.launch {
            combine(engine.external, engine.aimState, engine.link) { external, aim, link -> Triple(external, aim, link) }.collect { (external, aim, link) ->
                val state = when {
                    link is LinkState.Remote && engine.handedFromOtherApp -> BubbleState.OnScreen(link.receiverName)
                    external != null && aim is AimState.Targeted -> BubbleState.Aiming(aim.target.name, aim.verified)
                    external != null && aim is AimState.Searching -> BubbleState.Aiming(null, false)
                    else -> BubbleState.Hidden
                }
                bubble.show(state)
                notifications.notify(
                    ID,
                    notification(
                        when {
                            link is LinkState.Remote -> "Playing on ${link.receiverName}"
                            external != null -> "${external.item.title}: lift the phone toward a screen to send it"
                            else -> "Ready to send to a screen"
                        },
                        bringBack = link is LinkState.Remote,
                    ),
                )
            }
        }
        scope.launch {
            // Felt, not seen: SwipeTo's own screen isn't up to play these while another app is in front.
            if (Build.VERSION.SDK_INT < 31) return@launch
            val vibrator = getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            engine.haptics.collect { haptic ->
                if (engine.external.value == null && !engine.handedFromOtherApp) return@collect
                val effect = when (haptic) {
                    Haptic.TARGET_LOCKED -> android.os.VibrationEffect.EFFECT_TICK
                    Haptic.SENT -> android.os.VibrationEffect.EFFECT_HEAVY_CLICK
                    Haptic.BROUGHT_BACK -> android.os.VibrationEffect.EFFECT_CLICK
                }
                vibrator?.vibrate(android.os.VibrationEffect.createPredefined(effect))
            }
        }
        EventLog.i("background service started (bubble ${if (bubble.canShow) "allowed" else "NOT allowed: no overlay permission"})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BRING_BACK -> engine.bringBack()
            ACTION_STOP -> {
                engine.settings.setOtherApps(false)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bubble.remove()
        scope.cancel()
        EventLog.i("background service stopped")
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID, notification)
        }
    }

    private fun notification(text: String, bringBack: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("SwipeTo")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .apply {
                if (bringBack) addAction(Notification.Action.Builder(null, "Bring back", action(ACTION_BRING_BACK, 1)).build())
                addAction(Notification.Action.Builder(null, "Turn off", action(ACTION_STOP, 2)).build())
            }
            .build()
    }

    private fun action(name: String, code: Int) =
        PendingIntent.getService(this, code, Intent(this, SwipeToService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)

    private fun openApp() = startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private companion object {
        const val CHANNEL = "ready"
        const val ID = 1
        const val ACTION_BRING_BACK = "dev.swipeto.sender.BRING_BACK"
        const val ACTION_STOP = "dev.swipeto.sender.STOP"
    }
}
