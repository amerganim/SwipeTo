package dev.swipeto.sender.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import dev.swipeto.sender.MainViewModel
import dev.swipeto.sender.PairingRequest
import dev.swipeto.sender.ReceiverItem
import dev.swipeto.sender.aim.AimState
import dev.swipeto.sender.aim.Swipe
import dev.swipeto.sender.handoff.LinkState
import android.Manifest
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.platform.LocalDensity

@Composable
fun SwipeToScreen(viewModel: MainViewModel) {
    val video by viewModel.video.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    val receivers by viewModel.receivers.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val aim by viewModel.aimState.collectAsStateWithLifecycle()
    val debugOn by viewModel.settings.debugOverlay.collectAsStateWithLifecycle()
    val aimDebug by viewModel.aimDebug.collectAsStateWithLifecycle()
    val needsCamera by viewModel.needsCameraPermission.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val view = LocalView.current
    LaunchedEffect(Unit) { viewModel.notices.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { viewModel.hints.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { viewModel.haptics.collect { view.play(it) } }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), viewModel::onCameraPermissionResult)
    LaunchedEffect(needsCamera, video != null) {
        // Ask once there is something to send; until then the camera would have nothing to do.
        if (needsCamera && video != null) cameraPermission.launch(Manifest.permission.CAMERA)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onVideoPicked(uri)
    }
    val pickVideo = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }

    val scope = rememberCoroutineScope()
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SwipeTo", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = { showSettings = true }, modifier = Modifier.testTag("settings")) { Icon(Icons.Filled.Settings, "Settings") }
            }
            when (val state = link) {
                is LinkState.Remote -> RemoteControl(
                    state = state,
                    onTogglePlay = viewModel::togglePlay,
                    onSeek = viewModel::seekTo,
                    onBringBack = { viewModel.bringBack() },
                    modifier = Modifier.verticalSwipes(viewModel.swipes) { swipe ->
                        if (swipe == Swipe.DOWN) scope.launch { viewModel.onSwipe(swipe) }
                    },
                )
                else -> {
                    VideoArea(viewModel, hasVideo = video != null, aim = aim, onPick = pickVideo)
                    if (debugOn) DebugPanel(aimDebug, aim, viewModel::simulateAim)
                    video?.let {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(it.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = pickVideo) { Text("Change") }
                        }
                    }
                    ScreensList(
                        receivers = receivers,
                        busyWith = (state as? LinkState.Connecting)?.receiverName ?: (state as? LinkState.Pairing)?.receiverName,
                        canSend = video != null && state is LinkState.Idle,
                        onSend = { viewModel.sendTo(it.receiver) },
                    )
                }
            }
        }
    }

    pairing?.let { PairDialog(it, onSubmit = viewModel::submitCode, onCancel = viewModel::cancelPairing) }
    val navBar = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    if (showSettings) SettingsSheet(viewModel.settings, bottomInset = navBar) { showSettings = false }
}

@Composable
private fun VideoArea(viewModel: MainViewModel, hasVideo: Boolean, aim: AimState, onPick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val fly = remember { Animatable(0f) } // 0 = in place, 1 = flown off the top
    var heightPx by remember { mutableFloatStateOf(0f) }
    val targeted = aim is AimState.Targeted
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .onSizeChanged { heightPx = it.height.toFloat() }
            .then(if (targeted) Modifier.border(3.dp, Accent) else Modifier)
            .background(Color.Black)
            .verticalSwipes(viewModel.swipes) { swipe ->
                if (swipe != Swipe.UP || !hasVideo) return@verticalSwipes
                scope.launch {
                    if (viewModel.aimState.value !is AimState.Targeted) {
                        viewModel.onSwipe(swipe) // explains what to do instead
                        return@launch
                    }
                    // The video flies up towards the screen while the handoff runs; it drops back if it fails.
                    val flight = launch { fly.animateTo(1f, tween(280)) }
                    val sent = viewModel.onSwipe(swipe)
                    flight.join()
                    if (!sent) fly.animateTo(0f, tween(220))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (hasVideo) {
            AndroidView(
                factory = { context -> PlayerView(context).apply { player = viewModel.player } },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = -fly.value * heightPx * 1.2f
                        scaleX = 1f - 0.3f * fly.value
                        scaleY = 1f - 0.3f * fly.value
                    }
                    .alpha(1f - fly.value),
            )
            AimBanner(aim, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.VideoLibrary, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onPick, modifier = Modifier.testTag("pick")) { Text("Pick a video") }
            }
        }
    }
}

@Composable
private fun ScreensList(receivers: List<ReceiverItem>, busyWith: String?, canSend: Boolean, onSend: (ReceiverItem) -> Unit) {
    Text(
        "Screens nearby",
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    if (receivers.isEmpty()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Looking for screens on this Wi-Fi…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn {
        items(receivers, key = { it.receiver.id }) { item ->
            ListItem(
                leadingContent = { Icon(Icons.Filled.Tv, null) },
                headlineContent = { Text(item.receiver.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.paired) {
                            Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text("Paired · ${item.receiver.platform}")
                        } else {
                            Text("Not paired yet · ${item.receiver.platform}")
                        }
                    }
                },
                trailingContent = {
                    if (busyWith == item.receiver.name) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Button(onClick = { onSend(item) }, enabled = canSend, modifier = Modifier.testTag("send-${item.receiver.id}")) {
                            Icon(Icons.Filled.CastConnected, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Send")
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun RemoteControl(
    state: LinkState.Remote,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onBringBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs?.takeIf { it > 0 }

    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Tv, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Playing on ${state.receiverName}", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(state.title, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(32.dp))
        if (duration != null) {
            Slider(
                value = if (dragging) dragValue else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    onSeek((dragValue * duration).toLong())
                    dragging = false
                },
                modifier = Modifier.testTag("seek"),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(if (dragging) (dragValue * duration).toLong() else state.positionMs), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Text(formatTime(state.positionMs), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSeek(state.positionMs - 10_000) }) { Icon(Icons.Filled.Replay10, "Back 10 seconds", Modifier.size(32.dp)) }
            Spacer(Modifier.width(16.dp))
            FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(72.dp).testTag("play-pause")) {
                Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(40.dp))
            }
            Spacer(Modifier.width(16.dp))
            IconButton(onClick = { onSeek(state.positionMs + 10_000) }) { Icon(Icons.Filled.Forward10, "Forward 10 seconds", Modifier.size(32.dp)) }
        }
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onBringBack, modifier = Modifier.testTag("bring-back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Bring back to phone")
        }
        Spacer(Modifier.height(12.dp))
        SwipeDownHint()
    }
}

@Composable
private fun PairDialog(request: PairingRequest, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by rememberSaveable(request.attempt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pair with ${request.receiverName}") },
        text = {
            Column {
                Text(
                    if (request.attempt == 1) "Type the 6-digit code shown on the screen." else "That code didn't match. Try again.",
                    color = if (request.attempt == 1) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { value -> code = value.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 8.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().testTag("pair-code"),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(code) }, enabled = code.length == 6, modifier = Modifier.testTag("pair-confirm")) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
