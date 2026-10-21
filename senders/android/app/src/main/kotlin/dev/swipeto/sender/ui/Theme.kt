package dev.swipeto.sender.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF00B7FF)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00131F),
    secondary = Color(0xFF3A5BFF),
    background = Color(0xFF0B1220),
    surface = Color(0xFF0B1220),
    surfaceContainer = Color(0xFF141C2E),
    surfaceContainerHigh = Color(0xFF1B2438),
)

/** Always dark: the app is mostly a video player. */
@Composable
fun SwipeToTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)
