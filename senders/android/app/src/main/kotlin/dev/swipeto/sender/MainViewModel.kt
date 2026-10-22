package dev.swipeto.sender

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import dev.swipeto.protocol.MediaKind
import dev.swipeto.sender.aim.AimConfig
import dev.swipeto.sender.aim.AimController
import dev.swipeto.sender.aim.AimOption
import dev.swipeto.sender.aim.PointingFilter
import dev.swipeto.sender.aim.ResolvedBy
import dev.swipeto.sender.aim.AimPhase
import dev.swipeto.sender.aim.AimState
import dev.swipeto.sender.aim.CameraMarkerResolver
import dev.swipeto.sender.aim.CameraMarkerScanner
import dev.swipeto.sender.aim.FallbackResolver
import dev.swipeto.sender.aim.LinkTarget
import dev.swipeto.sender.aim.OrientationSensor
import dev.swipeto.sender.aim.RaiseToAimDetector
import dev.swipeto.sender.aim.StandbyLinks
import dev.swipeto.sender.aim.Swipe
import dev.swipeto.sender.aim.SwipeClassifier
import dev.swipeto.sender.aim.SwipeConfig
import dev.swipeto.sender.data.AppSettings
import dev.swipeto.sender.data.BearingStore
import dev.swipeto.sender.data.TrustStore
import dev.swipeto.sender.discovery.DiscoveredReceiver
import dev.swipeto.sender.discovery.ReceiverDiscovery
import dev.swipeto.sender.handoff.HandoffCoordinator
import dev.swipeto.sender.handoff.LinkState
import dev.swipeto.sender.handoff.LocalPlayback
import dev.swipeto.sender.handoff.PairingPrompt
import dev.swipeto.sender.handoff.SharedMedia
import dev.swipeto.sender.handoff.Target
import dev.swipeto.sender.media.ContentMediaFile
import dev.swipeto.sender.media.LocalMediaServer
import dev.swipeto.sender.media.ServerPublisher
import dev.swipeto.sender.net.SenderConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SwipeTo"

data class PickedVideo(val uri: Uri, val title: String, val mime: String, val size: Long, val durationMs: Long?)

data class ReceiverItem(val receiver: DiscoveredReceiver, val paired: Boolean)

data class PairingRequest(val receiverName: String, val attempt: Int, val reply: CompletableDeferred<String?>)

/** Live values for the sensor debug panel. */
data class AimDebug(
    val sensor: String,
    val elevationDeg: Float?,
    val phase: AimPhase,
    val headingDeg: Float? = null,
    /** "Laptop 121° (4)" for each paired screen with a learned direction. */
    val bearings: String = "",
)

enum class Haptic { TARGET_LOCKED, SENT, BROUGHT_BACK }

/** Owns the phone player, discovery, the media server and the handoff coordinator; survives rotation. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    private val trust = TrustStore(app)
    private val discovery = ReceiverDiscovery(app)
    private val server = LocalMediaServer()
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    val settings = AppSettings(app)
    private val orientation = OrientationSensor(app)
    private val detector = RaiseToAimDetector()
    val swipes = SwipeClassifier()
    private val scanner = CameraMarkerScanner(app)
    private val bearings = BearingStore(app)
    private val pointing = PointingFilter()
    @Volatile private var heading: Float? = null
    private var headingAtRaise: Float? = null
    private var aimJob: Job? = null
    private var foreground = false

    private val _haptics = MutableSharedFlow<Haptic>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val haptics: SharedFlow<Haptic> = _haptics.asSharedFlow()

    private val _aimDebug = MutableStateFlow(AimDebug(orientation.sensorName, null, AimPhase.IDLE))
    val aimDebug: StateFlow<AimDebug> = _aimDebug.asStateFlow()

    private val _hints = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val hints: SharedFlow<String> = _hints.asSharedFlow()

    private val _needsCameraPermission = MutableStateFlow(false)
    val needsCameraPermission: StateFlow<Boolean> = _needsCameraPermission.asStateFlow()

    private val _video = MutableStateFlow<PickedVideo?>(null)
    val video: StateFlow<PickedVideo?> = _video.asStateFlow()

    private val _pairing = MutableStateFlow<PairingRequest?>(null)
    val pairing: StateFlow<PairingRequest?> = _pairing.asStateFlow()

    private val _receivers = MutableStateFlow<List<ReceiverItem>>(emptyList())
    val receivers: StateFlow<List<ReceiverItem>> = _receivers.asStateFlow()

    private val coordinator = HandoffCoordinator(
        trust = trust,
        publisher = ServerPublisher(server),
        local = object : LocalPlayback {
            override val positionMs get() = player.currentPosition
            override val isPlaying get() = player.isPlaying || (player.playWhenReady && player.playbackState == ExoPlayer.STATE_BUFFERING)
            override fun pause() = player.pause()
            override fun resumeAt(positionMs: Long, play: Boolean) {
                player.seekTo(positionMs)
                player.playWhenReady = play
            }
        },
        prompt = PairingPrompt { name, attempt ->
            val request = PairingRequest(name, attempt, CompletableDeferred())
            _pairing.value = request
            try {
                request.reply.await()
            } finally {
                _pairing.value = null
            }
        },
        scope = viewModelScope,
        connect = { url -> SenderConnection.connect(url, trust.identity, log = { Log.i(TAG, it) }) },
        log = { Log.i(TAG, it) },
    )

    val link: StateFlow<LinkState> = coordinator.state
    val notices = coordinator.notices

    private val links = StandbyLinks(
        scope = viewModelScope,
        trust = trust,
        connect = { url -> SenderConnection.connect(url, trust.identity, log = { Log.i(TAG, it) }) },
        log = { Log.i(TAG, it) },
    )

    private val aim = AimController(
        scope = viewModelScope,
        links = links,
        candidates = {
            val facingNow = headingAtRaise ?: heading
            pairedTargets().map { AimOption(it, trust.lastUsed(it.id), pointing.facing(facingNow, bearings.get(it.id))) }
        },
        preferred = CameraMarkerResolver(
            scanner,
            timeoutMs = { AimConfig.forSensitivity(settings.sensitivity.value).markerSearchMs },
            enabled = { settings.cameraTargeting.value },
        ),
        fallback = FallbackResolver,
        onLocked = { targeted ->
            _haptics.tryEmit(Haptic.TARGET_LOCKED)
            // The camera proved which screen is in front: remember which way that is.
            if (targeted.verified) learnBearing(targeted.target.id, fromCamera = true)
        },
        log = { Log.i(TAG, it) },
    )

    val aimState: StateFlow<AimState> = aim.state

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var current: Network? = null

        override fun onLost(network: Network) {
            if (network == current) current = null
            viewModelScope.launch { coordinator.onConnectionLost() }
        }

        override fun onAvailable(network: Network) {
            // A different Wi-Fi network means a different set of screens. (The first callback is
            // just the network we started on.)
            val changed = current != null && current != network
            current = network
            if (changed) viewModelScope.launch(Dispatchers.Default) { discovery.restart() }
        }
    }

    init {
        discovery.start()
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            networkCallback,
        )
        viewModelScope.launch {
            discovery.receivers.collect { list ->
                _receivers.value = list.map { ReceiverItem(it, trust.isPaired(it.id)) }
                warmLinks()
            }
        }
        viewModelScope.launch {
            combine(settings.sensitivity, settings.cameraTargeting) { sensitivity, camera -> sensitivity to camera }.collect { (sensitivity, camera) ->
                detector.config = AimConfig.forSensitivity(sensitivity)
                swipes.config = SwipeConfig.forSensitivity(sensitivity)
                _needsCameraPermission.value = camera && !scanner.hasPermission
            }
        }
        viewModelScope.launch {
            // Aim only while the phone is playing locally; once the screen has the media we are a remote.
            combine(link, settings.raiseToAim) { state, enabled -> state is LinkState.Idle && enabled }.collect { idle ->
                if (idle) {
                    warmLinks()
                    updateAiming()
                } else {
                    stopAiming()
                }
            }
        }
    }

    /** From the activity: sensors, camera and standby links run only while the app is in front. */
    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            _needsCameraPermission.value = settings.cameraTargeting.value && !scanner.hasPermission
            warmLinks()
        } else {
            viewModelScope.launch { links.closeAll() }
        }
        updateAiming()
    }

    fun onCameraPermissionResult(granted: Boolean) {
        _needsCameraPermission.value = false
        if (!granted) _hints.tryEmit("Without the camera, SwipeTo picks the only (or last used) screen.")
    }

    /** A finished drag on the video (local) or the remote screen. */
    suspend fun onSwipe(swipe: Swipe): Boolean = when (swipe) {
        Swipe.UP -> swipeUp()
        Swipe.DOWN -> {
            if (link.value is LinkState.Remote) {
                coordinator.bringBack()
                _haptics.tryEmit(Haptic.BROUGHT_BACK)
                true
            } else {
                false
            }
        }
    }

    /** Debug overlay: behave as if the phone had just been raised (or lowered). */
    fun simulateAim(start: Boolean) {
        headingAtRaise = heading
        if (start) aim.onAimStart() else aim.endNow()
    }

    private suspend fun swipeUp(): Boolean {
        val picked = _video.value ?: return false
        Log.i(TAG, "swipe up while ${aimState.value::class.simpleName}")
        when (aimState.value) {
            is AimState.Targeted -> Unit
            is AimState.Searching -> {
                _hints.tryEmit("Point the phone at one screen.")
                return false
            }
            AimState.NoScreens -> {
                _hints.tryEmit("No paired screen is reachable. Pair one with Send first.")
                return false
            }
            AimState.Idle -> {
                _hints.tryEmit("Raise the phone toward a screen, then swipe up.")
                return false
            }
        }
        val chosenBy = (aimState.value as? AimState.Targeted)?.by
        val (target, conn) = aim.takeTarget() ?: run {
            Log.i(TAG, "swipe up: the aimed connection is gone")
            _hints.tryEmit("Lost the screen. Raise the phone again.")
            return false
        }
        val receiver = discovery.receivers.value.firstOrNull { it.id == target.id } ?: run {
            Log.i(TAG, "swipe up: ${target.name} is no longer discovered")
            conn.close()
            return false
        }
        Log.i(TAG, "swipe up: handing off to ${target.name}")
        val sent = coordinator.sendTo(
            Target(receiver.id, receiver.name, receiver.webSocketUrl, receiver.host),
            sharedMedia(picked),
            ready = conn,
        )
        if (sent) {
            trust.markUsed(receiver.id)
            _haptics.tryEmit(Haptic.SENT)
            // A deliberate send toward a known screen refines its direction (never from a guess).
            if (chosenBy == ResolvedBy.COMPASS || chosenBy == ResolvedBy.CAMERA) learnBearing(receiver.id, fromCamera = false)
        }
        detector.reset()
        return sent
    }

    private fun updateAiming() {
        val wanted = foreground && settings.raiseToAim.value && link.value is LinkState.Idle && orientation.available
        if (!wanted) {
            stopAiming()
            return
        }
        if (aimJob?.isActive == true) return
        aimJob = viewModelScope.launch {
            var lastDebug = 0L
            orientation.samples().collect { sample ->
                heading = sample.headingDeg
                if (sample.timeMs - lastDebug >= 80) {
                    lastDebug = sample.timeMs
                    _aimDebug.value = AimDebug(orientation.sensorName, sample.elevationDeg, detector.phase, sample.headingDeg, bearingsText())
                    // With the debug panel on, keep a trace for tuning thresholds against real motion.
                    if (settings.debugOverlay.value) Log.d("SwipeToTrace", "%d %.1f %s".format(sample.timeMs, sample.elevationDeg, detector.phase))
                }
                when (detector.onSample(sample)) {
                    AimPhase.AIMING -> {
                        headingAtRaise = sample.headingDeg
                        Log.i(TAG, "raise detected at %.1f°, heading %s".format(sample.elevationDeg, sample.headingDeg?.let { "%.0f°".format(it) } ?: "unknown"))
                        if (_video.value != null) aim.onAimStart()
                    }
                    AimPhase.IDLE -> {
                        Log.i(TAG, "lowered at %.1f°".format(sample.elevationDeg))
                        aim.onAimEnd()
                    }
                    null -> Unit
                }
            }
        }
    }

    private fun stopAiming() {
        aimJob?.cancel()
        aimJob = null
        detector.reset()
        if (aimState.value !is AimState.Idle) aim.endNow()
        _aimDebug.value = _aimDebug.value.copy(phase = AimPhase.IDLE)
    }

    /** Debug panel: forget every learned screen direction (after moving a screen). */
    fun forgetDirections() {
        bearings.forgetAll()
        _hints.tryEmit("Directions forgotten. Point the camera at each screen once to teach them again.")
    }

    private fun learnBearing(receiverId: String, fromCamera: Boolean) {
        val now = headingAtRaise ?: heading ?: return
        val learned = pointing.learn(bearings.get(receiverId), now, fromCamera)
        bearings.put(receiverId, learned)
        Log.i(TAG, "learned direction of $receiverId: %.0f° (%d samples)".format(learned.headingDeg, learned.samples))
    }

    private fun bearingsText(): String = pairedTargets().mapNotNull { t ->
        bearings.get(t.id)?.let { "${t.name} %.0f° (%d)".format(it.headingDeg, it.samples) }
    }.joinToString().ifEmpty { "none learned" }

    private fun pairedTargets(): List<LinkTarget> =
        discovery.receivers.value.filter { trust.isPaired(it.id) }.map { LinkTarget(it.id, it.name, it.webSocketUrl) }

    private fun warmLinks() {
        if (!foreground || link.value !is LinkState.Idle || _video.value == null) return
        viewModelScope.launch { links.warm(pairedTargets()) }
    }

    private fun sharedMedia(picked: PickedVideo): SharedMedia {
        val file = ContentMediaFile(getApplication<Application>().contentResolver, picked.uri, picked.mime, picked.size)
        return SharedMedia(file, MediaKind.VIDEO, picked.title, picked.durationMs ?: player.duration.takeIf { it > 0 })
    }

    fun onVideoPicked(uri: Uri) = viewModelScope.launch {
        val picked = withContext(Dispatchers.IO) { describe(uri) } ?: return@launch
        if (link.value !is LinkState.Idle) coordinator.bringBack()
        _video.value = picked
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
        warmLinks()
        updateAiming()
    }

    fun sendTo(receiver: DiscoveredReceiver) = viewModelScope.launch {
        val picked = _video.value ?: return@launch
        // A standby link to this screen would refuse a second hello; use it instead of a new connection.
        val ready = links.take(receiver.id)
        val sent = coordinator.sendTo(Target(receiver.id, receiver.name, receiver.webSocketUrl, receiver.host), sharedMedia(picked), ready)
        if (sent) {
            trust.markUsed(receiver.id)
            _haptics.tryEmit(Haptic.SENT)
            refreshPaired()
        }
    }

    fun submitCode(code: String) {
        _pairing.value?.reply?.complete(code)
    }

    fun cancelPairing() {
        _pairing.value?.reply?.complete(null)
    }

    fun togglePlay() = coordinator.togglePlay()

    fun seekTo(positionMs: Long) = coordinator.seekTo(positionMs)

    fun bringBack() = viewModelScope.launch {
        coordinator.bringBack()
        _haptics.tryEmit(Haptic.BROUGHT_BACK)
    }

    private fun refreshPaired() {
        _receivers.value = _receivers.value.map { it.copy(paired = trust.isPaired(it.receiver.id)) }
    }

    private fun describe(uri: Uri): PickedVideo? {
        val resolver = getApplication<Application>().contentResolver
        var title = "Video"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { title = it.substringBeforeLast('.') }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        if (size < 0) size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
        val mime = resolver.getType(uri)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        val (duration, embeddedTitle) = runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(getApplication(), uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() to
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            }
        }.getOrDefault(null to null)
        // The Photo Picker often names files by media-store id ("1000033915"), which means nothing to anyone.
        if (title.all(Char::isDigit)) title = embeddedTitle?.takeIf { it.isNotBlank() } ?: "Video"
        return PickedVideo(uri, title.take(200), mime, size, duration)
    }

    override fun onCleared() {
        stopAiming()
        coordinator.onConnectionLost()
        connectivity.unregisterNetworkCallback(networkCallback)
        discovery.stop()
        server.stop()
        player.release()
    }
}
