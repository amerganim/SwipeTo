package dev.swipeto.sender.apps

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Exists only so Android lets [ActiveMediaWatcher] see other apps' media sessions (the "Notification
 * access" the user grants). It deliberately does nothing with notifications: their text is never
 * read, stored or sent anywhere.
 */
class MediaNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit
}
