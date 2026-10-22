package dev.swipeto.sender.apps

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.swipeto.sender.SwipeToApp

/**
 * Exists only so Android lets [ActiveMediaWatcher] see other apps' media sessions (the "Notification
 * access" the user grants). It deliberately does nothing with notifications: their text is never
 * read, stored or sent anywhere.
 */
class MediaNotificationListener : NotificationListenerService() {
    /**
     * Android binds this after a reboot, an app update, or the grant: the moment to start watching
     * other apps and the background service, without waiting for SwipeTo to be opened. (On the A15
     * test phone, after an update nothing worked from Gallery or YouTube until SwipeTo was opened.)
     */
    override fun onListenerConnected() {
        (application as SwipeToApp).engine.startInBackground()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit
}
