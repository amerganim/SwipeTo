package dev.swipeto.sender

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.swipeto.sender.apps.SharedLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Share → SwipeTo from YouTube, a browser or any app with a link: sends it to the screen used last,
 * at the position the app is playing; with no screen around, keeps it for the next lift and swipe.
 * No window of its own: a short message, and back to the app you were in.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        val link = SharedLink.parse(text)
        if (link == null) {
            Toast.makeText(this, "SwipeTo: that share has no video link in it.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val engine = (application as SwipeToApp).engine
        CoroutineScope(Dispatchers.Main).launch {
            val message = engine.onLinkShared(link, intent.getStringExtra(Intent.EXTRA_SUBJECT))
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
