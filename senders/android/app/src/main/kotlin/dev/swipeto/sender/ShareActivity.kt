package dev.swipeto.sender

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
        val engine = (application as SwipeToApp).engine
        val media = sharedMedia(intent)
        if (media.isNotEmpty()) {
            // A photo or video from Gallery (or any app): send exactly that.
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(applicationContext, engine.onMediaShared(media), Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        val link = SharedLink.parse(text)
        if (link == null) {
            Toast.makeText(this, "SwipeTo: that share has no video link in it.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val message = engine.onLinkShared(link, intent.getStringExtra(Intent.EXTRA_SUBJECT))
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    /** Photos or videos shared as files (EXTRA_STREAM), one or several. */
    private fun sharedMedia(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val type = intent.type.orEmpty()
        if (!type.startsWith("image/") && !type.startsWith("video/") && type != "*/*") return emptyList()
        @Suppress("DEPRECATION")
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
    }
}
