package dev.swipeto.sender

import android.app.Application
import dev.swipeto.sender.data.EventLog

class SwipeToApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
    }
}
