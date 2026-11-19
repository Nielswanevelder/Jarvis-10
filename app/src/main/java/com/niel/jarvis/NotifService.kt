package com.niel.jarvis

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifService : NotificationListenerService() {
    companion object {
        var instance: NotifService? = null
        var listener: ((StatusBarNotification) -> Unit)? = null
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        listener?.invoke(sbn)
    }
}
