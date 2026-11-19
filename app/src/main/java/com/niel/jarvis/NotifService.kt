package com.niel.jarvis

import android.service.notification.NotificationListenerService

class NotifService : NotificationListenerService() {
    companion object {
        var instance: NotifService? = null
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }
}
