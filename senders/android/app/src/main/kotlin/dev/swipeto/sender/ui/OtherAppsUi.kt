package dev.swipeto.sender.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swipeto.sender.apps.MediaNotificationListener
import dev.swipeto.sender.apps.SessionSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings section for swiping from other apps: what access is needed and why, a button to grant it,
 * and (debug) every field each playing app shares through its media session.
 */
@Composable
fun OtherAppsSection(hasAccess: StateFlow<Boolean>, sessions: StateFlow<List<SessionSnapshot>>, showFields: Boolean) {
    val access by hasAccess.collectAsStateWithLifecycle()
    val list by sessions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Swipe from other apps", style = MaterialTheme.typography.titleSmall)
        Text(
            "To send what you watch in Gallery, YouTube and other apps, SwipeTo needs Notification access. " +
                "It uses it only to see which app is playing, the title and the position, through Android's media controls. " +
                "It never reads, stores or sends the text of your notifications.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (access) {
            Text("Access granted", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        } else {
            OutlinedButton(
                onClick = {
                    val component = ComponentName(context, MediaNotificationListener::class.java)
                    val intent = if (Build.VERSION.SDK_INT >= 30) {
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
                    } else {
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    }
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                modifier = Modifier.testTag("grant-media-access"),
            ) { Text("Allow Notification access…") }
        }
        if (access && showFields) {
            if (list.isEmpty()) Text("No app is playing anything right now.", style = MaterialTheme.typography.bodySmall)
            list.forEach { SessionCard(it) }
        }
    }
}

@Composable
private fun SessionCard(s: SessionSnapshot) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(10.dp)) {
            val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            Text("${s.appLabel} (${s.packageName})", style = MaterialTheme.typography.labelLarge)
            Text("state     ${s.state} at ${s.positionNow()?.let(::clock) ?: "?"} of ${s.durationMs?.let(::clock) ?: "?"}", style = mono)
            Text("title     ${s.title ?: "—"}", style = mono)
            Text("mediaId   ${s.mediaId ?: "—"}", style = mono)
            (s.metadata + s.details).forEach { (k, v) -> Text("$k = $v", style = mono) }
        }
    }
}

private fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
