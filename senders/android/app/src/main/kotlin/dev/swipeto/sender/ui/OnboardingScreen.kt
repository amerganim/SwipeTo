package dev.swipeto.sender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwipeUp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class Page(val icon: ImageVector, val title: String, val body: String)

private val pages = listOf(
    Page(
        Icons.Filled.Tv,
        "Put it on the big screen",
        "SwipeTo moves the video or photos you are watching to a TV or PC nearby. It carries on from the same moment, and your phone becomes the remote.",
    ),
    Page(
        Icons.Filled.Pin,
        "Pair once",
        "Install SwipeTo on the screen and join the same Wi-Fi. The first time, tap Send next to the screen and type the 6-digit code it shows.",
    ),
    Page(
        Icons.Filled.SwipeUp,
        "Lift and swipe up",
        "While a video plays, lift the phone toward the screen. When it glows, swipe up on the video. Swipe down to bring it back.",
    ),
    Page(
        Icons.Filled.Share,
        "YouTube and websites",
        "Watching in YouTube or a browser? Tap Share, then SwipeTo. The screen opens it at the same second, and your phone's notification becomes the remote.",
    ),
    Page(
        Icons.Filled.PhotoLibrary,
        "Photos too",
        "Pick several photos, send them the same way, then swipe left or right to page through them on the screen. SwipeTo may ask for the camera: it only looks for the code on the screen you point at.",
    ),
)

/** First run: four short pages, skippable. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index]
    val last = index == pages.lastIndex
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = onDone, modifier = Modifier.testTag("onboarding-skip")) { Text("Skip") }
            }
            Spacer(Modifier.weight(1f))
            Icon(page.icon, null, Modifier.size(88.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(28.dp))
            Text(page.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(page.body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${index + 1} of ${pages.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { if (last) onDone() else index++ },
                modifier = Modifier.fillMaxWidth().testTag("onboarding-next"),
            ) { Text(if (last) "Get started" else "Next") }
        }
    }
}
