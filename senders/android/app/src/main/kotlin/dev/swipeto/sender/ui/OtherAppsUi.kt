package dev.swipeto.sender.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swipeto.sender.MainViewModel
import dev.swipeto.sender.apps.MediaNotificationListener
import dev.swipeto.sender.apps.SessionSnapshot

/**
 * Settings section for swiping from other apps: an on/off switch, each permission it needs with
 * why and a button to grant it, and (debug) every field each playing app shares.
 */
@Composable
fun OtherAppsSection(engine: MainViewModel, showFields: Boolean) {
    val context = LocalContext.current
    val enabled by engine.settings.otherApps.collectAsStateWithLifecycle()
    val access by engine.mediaAccess.collectAsStateWithLifecycle()
    val list by engine.mediaWatcher.sessions.collectAsStateWithLifecycle()
    val external by engine.external.collectAsStateWithLifecycle()
    val unresolved by engine.unresolved.collectAsStateWithLifecycle()
    // Grants happen on Android's own screens: look again whenever we come back.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val overlay = remember(resumed) { Settings.canDrawOverlays(context) }
    val videos = remember(resumed) { engine.mediaResolver.hasPermission }
    val notify = remember(resumed) { Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS) }
    val askVideos = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumed++ }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumed++ }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Swipe from other apps", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Watching in Gallery or another app? Lift the phone toward a screen and swipe up on the SwipeTo bubble.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = engine.settings::setOtherApps)
        }
        if (!enabled) return@Column

        Requirement(
            done = access,
            title = "See what's playing",
            why = "Notification access lets SwipeTo see which app plays what and where, through Android's media controls. " +
                "It never reads, stores or sends your notifications.",
            button = "Allow Notification access…",
            tag = "grant-media-access",
        ) {
            val component = ComponentName(context, MediaNotificationListener::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Requirement(
            done = videos,
            title = "Find the video",
            why = "Access to videos lets SwipeTo find the file another app is playing, to stream it to the screen.",
            button = "Allow access to videos…",
            tag = "grant-videos",
        ) { askVideos.launch(engine.mediaResolver.permission) }
        Requirement(
            done = overlay,
            title = "Swipe bubble",
            why = "\"Display over other apps\" lets SwipeTo show the swipe bubble over Gallery or YouTube while you aim.",
            button = "Allow display over other apps…",
            tag = "grant-overlay",
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        Requirement(
            done = notify,
            title = "Status notification",
            why = "A quiet notification shows SwipeTo is ready, and offers Bring back while a screen plays.",
            button = "Allow notifications…",
            tag = "grant-notifications",
        ) { if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }

        external?.let { Text("Ready to send: ${it.item.title} (${it.app})", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        unresolved?.let { Text("${it.appLabel} is playing something SwipeTo can't identify yet.", style = MaterialTheme.typography.bodySmall) }
        if (access && showFields) {
            if (list.isEmpty()) Text("No app is playing anything right now.", style = MaterialTheme.typography.bodySmall)
            list.forEach { SessionCard(it) }
        }
    }
}

@Composable
private fun Requirement(done: Boolean, title: String, why: String, button: String, tag: String, grant: () -> Unit) {
    Column {
        Text(if (done) "✓  $title" else title, style = MaterialTheme.typography.labelLarge, color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (!done) {
            Text(why, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = grant, modifier = Modifier.padding(top = 4.dp).testTag(tag)) { Text(button) }
        }
    }
}

private fun granted(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

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
