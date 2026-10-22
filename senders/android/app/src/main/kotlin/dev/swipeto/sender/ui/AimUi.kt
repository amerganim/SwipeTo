package dev.swipeto.sender.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swipeto.sender.AimDebug
import dev.swipeto.sender.Haptic
import dev.swipeto.sender.aim.AimState
import dev.swipeto.sender.aim.ResolvedBy
import dev.swipeto.sender.aim.Sensitivity
import dev.swipeto.sender.aim.Swipe
import dev.swipeto.sender.aim.SwipeClassifier
import dev.swipeto.sender.data.AppSettings
import dev.swipeto.sender.data.EventLog

/**
 * Reports deliberate vertical swipes over this element without taking touches away from what is
 * underneath (the player still gets its taps): it only watches the Initial pass, never consumes.
 *
 * The gesture loop outlives recompositions, so it calls the *latest* [onSwipe] (found on the A15:
 * holding on to the first one meant every swipe still saw "no video loaded").
 */
fun Modifier.verticalSwipes(classifier: SwipeClassifier, onSwipe: (Swipe) -> Unit): Modifier = composed {
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    swipeGestures(classifier) { currentOnSwipe(it) }
}

private fun Modifier.swipeGestures(classifier: SwipeClassifier, onSwipe: (Swipe) -> Unit): Modifier = pointerInput(classifier) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var last = down
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPosition(change.uptimeMillis, change.position)
            last = change
            if (!change.pressed) break
        }
        val velocity = tracker.calculateVelocity()
        val dx = (last.position.x - down.position.x) / density
        val dy = (last.position.y - down.position.y) / density
        val duration = last.uptimeMillis - down.uptimeMillis
        val swipe = classifier.classify(dx, dy, duration, velocity.y / density)
        EventLog.d("drag dx=%.0fdp dy=%.0fdp %dms vy=%.0fdp/s -> %s".format(dx, dy, duration, velocity.y / density, swipe))
        swipe?.let(onSwipe)
    }
}

/** What aiming is doing, shown over the video. */
@Composable
fun AimBanner(state: AimState, modifier: Modifier = Modifier) {
    AnimatedVisibility(state !is AimState.Idle, modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.7f)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val (icon, text) = when (state) {
                    is AimState.Searching -> Icons.Filled.Search to "Looking for the screen…"
                    is AimState.Targeted -> Icons.Filled.ArrowUpward to "Swipe up to send to ${state.target.name}"
                    AimState.NoScreens -> Icons.Filled.WifiOff to "No paired screen nearby"
                    AimState.Idle -> Icons.Filled.Search to ""
                }
                Icon(icon, null, Modifier.size(18.dp), tint = Accent)
                Spacer(Modifier.width(8.dp))
                Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
                if (state is AimState.Targeted && state.verified) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Verified, "Screen confirmed", Modifier.size(16.dp), tint = Accent)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(settings: AppSettings, bottomInset: Dp, onDismiss: () -> Unit) {
    val sensitivity by settings.sensitivity.collectAsStateWithLifecycle()
    val raise by settings.raiseToAim.collectAsStateWithLifecycle()
    val camera by settings.cameraTargeting.collectAsStateWithLifecycle()
    val debug by settings.debugOverlay.collectAsStateWithLifecycle()
    // Fully expanded: a half-open sheet would clip the last settings behind the navigation bar.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // The app draws edge to edge and the sheet reports no insets of its own, so the caller passes the
        // navigation bar height measured on the main screen; otherwise the last switch sits under it.
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = bottomInset + 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            SettingSwitch("Raise to aim", "Lift the phone toward a screen, then swipe up to send.", raise, settings::setRaiseToAim)
            Column {
                Text("Sensitivity", style = MaterialTheme.typography.titleSmall)
                Text("How easily raising and swiping trigger.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Sensitivity.entries.forEachIndexed { i, value ->
                        SegmentedButton(
                            selected = sensitivity == value,
                            onClick = { settings.setSensitivity(value) },
                            shape = SegmentedButtonDefaults.itemShape(i, Sensitivity.entries.size),
                        ) { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
            }
            SettingSwitch(
                "Find the screen with the camera",
                "When several screens are nearby, the camera reads the code on the one you point at. Nothing is recorded.",
                camera,
                settings::setCameraTargeting,
            )
            SettingSwitch("Sensor debug", "Show live sensor values and an Aim button.", debug, settings::setDebugOverlay)
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Live sensor values and manual aim controls, for tuning thresholds on a real phone. */
@Composable
fun DebugPanel(debug: AimDebug, aim: AimState, onAim: (Boolean) -> Unit, onForget: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp)) {
            val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            Text("sensor     ${debug.sensor}", style = mono)
            Text("elevation  ${debug.elevationDeg?.let { "%+6.1f°".format(it) } ?: "—"}", style = mono)
            Text("heading    ${debug.headingDeg?.let { "%.0f°".format(it) } ?: "— (no compass)"}", style = mono)
            Text("learned    ${debug.bearings}", style = mono)
            Text("detector   ${debug.phase}", style = mono)
            Text("aim        ${aimLabel(aim)}", style = mono)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAim(true) }) { Text("Aim now") }
                OutlinedButton(onClick = { onAim(false) }) { Text("End aim") }
                OutlinedButton(onClick = onForget) { Text("Forget directions") }
            }
        }
    }
}

private fun aimLabel(aim: AimState) = when (aim) {
    AimState.Idle -> "idle"
    AimState.NoScreens -> "no paired screen reachable"
    is AimState.Searching -> "searching ${aim.screens.joinToString()}"
    is AimState.Targeted -> "${aim.target.name} by ${aim.by.name.lowercase()}${if (aim.verified) ", verified" else if (aim.by == ResolvedBy.CAMERA) ", NOT verified" else ""}"
}

fun View.play(haptic: Haptic) {
    val constant = when (haptic) {
        Haptic.TARGET_LOCKED -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        Haptic.SENT -> HapticFeedbackConstants.LONG_PRESS
        Haptic.BROUGHT_BACK -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CONTEXT_CLICK
    }
    performHapticFeedback(constant)
}

/** Shown for a moment on the remote: the other gesture. */
@Composable
fun SwipeDownHint(modifier: Modifier = Modifier) {
    Text("Swipe down to bring it back", modifier.background(Color.Transparent), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
}
