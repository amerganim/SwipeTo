package dev.swipeto.sender

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.swipeto.sender.data.EventLog

/**
 * Owns the one [MainViewModel] for the whole process, so SwipeTo's own screen and the background
 * service (swiping from other apps, Phase 5B) share the same player, links and handoff.
 */
class SwipeToApp : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    val engine: MainViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[MainViewModel::class.java]
    }

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
    }
}
