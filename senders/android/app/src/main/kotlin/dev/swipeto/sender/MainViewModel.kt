package dev.swipeto.sender

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import dev.swipeto.protocol.MediaInfo
import dev.swipeto.protocol.MediaKind
import dev.swipeto.protocol.Prefetch
import dev.swipeto.protocol.Prepare
import dev.swipeto.sender.aim.Preparer
import dev.swipeto.sender.apps.ActiveMediaWatcher
import dev.swipeto.sender.apps.ExternalPlayback
import dev.swipeto.sender.apps.ForegroundApp
import dev.swipeto.sender.apps.RecentMedia
import dev.swipeto.sender.apps.LocalMediaResolver
import dev.swipeto.sender.apps.SessionSnapshot
import dev.swipeto.sender.apps.SharedLink
import dev.swipeto.protocol.Transition
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
import dev.swipeto.sender.data.EventLog
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
import dev.swipeto.sender.net.ReceiverException
import dev.swipeto.sender.net.SenderConnection
import okhttp3.OkHttpClient
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

private const val TAG = "SwipeTo"

/** How long a share waits for the screen used last to be discovered (SwipeTo may have just started). */
private const val SHARE_DISCOVERY_MS = 4_000L

/** Photos are decoded down to about this many pixels on the long side for the phone's own screen. */
private const val PHOTO_MAX_PX = 1600

/** A picked video or photo, or ([app] set) another app's content such as a YouTube video. */
data class PickedItem(val uri: Uri, val title: String, val mime: String, val size: Long, val durationMs: Long?, val kind: MediaKind, val app: MediaInfo? = null)

/** Video apps whose playing item a share names (they don't say it themselves): a raise there explains Share → SwipeTo. */
val SHAREABLE_APPS = setOf(
    "com.google.android.youtube", "com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox",
    "com.microsoft.emmx", "com.opera.browser", "com.brave.browser", "com.google.android.apps.youtube.music",
)

/** Apps whose content a screen opens with that service's own player (docs/protocol.md, 5.4). */
private val SERVICES = mapOf("com.google.android.youtube" to "youtube")

/** A link shared to SwipeTo while [packageName] played [title] (null: nothing was playing). */
data class Shared(val link: SharedLink, val packageName: String?, val title: String?, val subject: String?)

/**
 * What [session] plays, as app content for a screen to open itself; null if it is not such an app or
 * says nothing usable. A link shared while this same thing played names it exactly (YouTube by id, or
 * a page for the screen's browser); without one, YouTube is found by title and channel.
 */
fun appContent(session: SessionSnapshot, shared: Shared? = null): PickedItem? {
    val title = session.title?.takeIf { it.isNotBlank() }
    val bound = shared?.takeIf { it.packageName == session.packageName && (it.title == null || it.title == title) }
    val media = when {
        bound != null -> MediaInfo.app(
            bound.link.service,
            (title ?: bound.subject ?: bound.link.url).take(200),
            session.subtitle?.take(200),
            contentId = bound.link.contentId,
            contentUrl = bound.link.url,
            durationMs = session.durationMs,
        )
        title != null && SERVICES[session.packageName] != null ->
            MediaInfo.app(SERVICES.getValue(session.packageName), title.take(200), session.subtitle?.take(200), durationMs = session.durationMs)
        else -> return null
    }
    return PickedItem(Uri.EMPTY, media.title, "", 0, session.durationMs, MediaKind.APP, media)
}

/** A shared link with nothing playing: the screen opens it from the start. */
fun sharedOnly(shared: Shared): PickedItem {
    val media = MediaInfo.app(shared.link.service, (shared.subject ?: shared.link.url).take(200), contentId = shared.link.contentId, contentUrl = shared.link.url)
    return PickedItem(Uri.EMPTY, media.title, "", 0, null, MediaKind.APP, media)
}

/** What was picked: one item, or several to page through with left/right swipes. */
data class Album(val items: List<PickedItem>, val index: Int) {
    val current: PickedItem get() = items[index]
    val isAlbum: Boolean get() = items.size > 1
}

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

/**
 * What a swipe would send right now and the player it comes from: SwipeTo's own (in front), or
 * another app's ([app] = its package) found through its media session.
 */
data class Source(val item: PickedItem, val playback: LocalPlayback, val app: String? = null)

/**
 * Owns the phone player, discovery, the media server and the handoff coordinator. One per process
 * ([SwipeToApp.engine]): SwipeTo's own screen and the background service share it.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    private val trust = TrustStore(app)
    private val discovery = ReceiverDiscovery(app)
    private val server = LocalMediaServer()
    private val publisher = ServerPublisher(server) { socket -> wifi?.bindSocket(socket) }

    /**
     * The Wi-Fi network, once seen. Screens are reached over it even while Android routes the rest
     * through mobile data (Wi-Fi without internet, or just back and not validated yet): found on the
     * A15, where reconnects after a Wi-Fi blip went out over mobile data and never reached the PC.
     */
    @Volatile private var wifi: Network? = null
    @Volatile private var wifiSeen = false
    private var wifiClient: Pair<Network, OkHttpClient>? = null
    private val files = mutableMapOf<Uri, ContentMediaFile>()
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    val settings = AppSettings(app)

    /** What other apps are playing (Phase 5B); empty until the user grants Notification access. */
    val mediaWatcher = ActiveMediaWatcher(app) { EventLog.i(it) }
    private val _mediaAccess = MutableStateFlow(mediaWatcher.hasAccess)
    val mediaAccess: StateFlow<Boolean> = _mediaAccess.asStateFlow()
    val mediaResolver = LocalMediaResolver(app)
    val foregroundApp = ForegroundApp(app)
    val recentMedia = RecentMedia(app)

    /** A gallery app is in front (from the background service): a raise offers the newest photos and videos. */
    private val _galleryOpen = MutableStateFlow(false)
    val galleryOpen: StateFlow<Boolean> = _galleryOpen.asStateFlow()

    /** The screen the phone was last aimed at, for a pick made on the bubble after lowering it. */
    @Volatile private var lastTarget: LinkTarget? = null

    /** Another app's media, resolved to a file on the phone, while it plays; null otherwise. */
    private val _external = MutableStateFlow<Source?>(null)
    val external: StateFlow<Source?> = _external.asStateFlow()

    /** Another app plays something SwipeTo can't tell apart (no title, or no single matching file). */
    private val _unresolved = MutableStateFlow<SessionSnapshot?>(null)
    val unresolved: StateFlow<SessionSnapshot?> = _unresolved.asStateFlow()
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

    private val _album = MutableStateFlow<Album?>(null)
    val album: StateFlow<Album?> = _album.asStateFlow()

    /** The item on the phone now (shown, or the one the screen has). */
    private val _media = MutableStateFlow<PickedItem?>(null)
    val media: StateFlow<PickedItem?> = _media.asStateFlow()

    /** The current photo, decoded for the phone's screen; null for a video. */
    private val _photo = MutableStateFlow<Bitmap?>(null)
    val photo: StateFlow<Bitmap?> = _photo.asStateFlow()

    private val _pairing = MutableStateFlow<PairingRequest?>(null)
    val pairing: StateFlow<PairingRequest?> = _pairing.asStateFlow()

    private val _receivers = MutableStateFlow<List<ReceiverItem>>(emptyList())
    val receivers: StateFlow<List<ReceiverItem>> = _receivers.asStateFlow()

    /** Nothing playing on the phone (a link shared from a page that isn't playing): the screen starts it from the top. */
    private object NoPlayback : LocalPlayback {
        override val positionMs = 0L
        override val isPlaying = true
        override fun pause() = Unit
        override fun resumeAt(positionMs: Long, play: Boolean) = Unit
    }

    /** SwipeTo's own player. */
    private val inAppPlayback = object : LocalPlayback {
        override val positionMs get() = player.currentPosition
        override val isPlaying get() = player.isPlaying || (player.playWhenReady && player.playbackState == ExoPlayer.STATE_BUFFERING)
        override fun pause() = player.pause()
        override fun resumeAt(positionMs: Long, play: Boolean) {
            player.seekTo(positionMs)
            player.playWhenReady = play
        }
    }

    /** The player the current handoff came from; bring-back resumes there. */
    @Volatile private var handedFrom: LocalPlayback = inAppPlayback

    /** The screen has media that came from another app (the bubble offers bring-back). */
    val handedFromOtherApp: Boolean get() = handedFrom is ExternalPlayback || sentFromGallery

    /** The current handoff was picked on the bubble over Gallery. */
    @Volatile private var sentFromGallery = false

    private val coordinator = HandoffCoordinator(
        trust = trust,
        publisher = publisher,
        local = object : LocalPlayback {
            override val positionMs get() = handedFrom.positionMs
            override val isPlaying get() = handedFrom.isPlaying
            override fun pause() = handedFrom.pause()
            override fun resumeAt(positionMs: Long, play: Boolean) = handedFrom.resumeAt(positionMs, play)
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
        connect = ::connectToScreen,
        log = { EventLog.i(it) },
    )

    val link: StateFlow<LinkState> = coordinator.state
    val notices = coordinator.notices

    private val links = StandbyLinks(
        scope = viewModelScope,
        trust = trust,
        connect = ::connectToScreen,
        log = { EventLog.i(it) },
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
            // Android allows the camera only while SwipeTo is in front; over other apps the compass decides.
            enabled = { settings.cameraTargeting.value && foreground },
        ),
        fallback = FallbackResolver,
        onLocked = { targeted ->
            lastTarget = targeted.target
            _haptics.tryEmit(Haptic.TARGET_LOCKED)
            // The camera proved which screen is in front: remember which way that is.
            if (targeted.verified) learnBearing(targeted.target.id, fromCamera = true)
        },
        prefetch = { target -> prefetchFor(target) },
        // A prefetch published the file for that screen; without a handoff it must stop being served
        // (unless it is being prepared there, which keeps the same URL).
        onEnded = { if (link.value is LinkState.Idle && !preparer.active) publisher.endSession() },
        log = { EventLog.i(it) },
    )

    val aimState: StateFlow<AimState> = aim.state

    private val preparer = Preparer(
        scope = viewModelScope,
        links = links,
        target = ::screenToPrepare,
        what = { target ->
            prefetchFor(target)?.takeIf { source()?.playback?.isPlaying == true && settings.prepareScreen.value && onUnmeteredWifi() }
                ?.let { Prepare(it.media, it.positionMs) }
        },
        log = { EventLog.i(it) },
    )

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var current: Network? = null

        override fun onLost(network: Network) {
            if (network == current) current = null
            if (network == wifi) wifi = null
            // The screen keeps playing; the coordinator reconnects once Wi-Fi is back (or takes it back here).
            viewModelScope.launch { coordinator.onNetworkLost() }
        }

        override fun onAvailable(network: Network) {
            // A different Wi-Fi network means a different set of screens. (The first callback is
            // just the network we started on.)
            val changed = current != null && current != network
            current = network
            wifi = network
            wifiSeen = true
            EventLog.i("Wi-Fi available")
            coordinator.onNetworkAvailable()
            if (changed) viewModelScope.launch(Dispatchers.Default) { discovery.restart() }
        }
    }

    init {
        // Started for a share or by Android (notification access) as well as by SwipeTo's screen.
        mediaWatcher.start()
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
            // Another app plays: find the file, so a swipe can send it (and SwipeTo can pause that app).
            mediaWatcher.playing.collect { session -> updateExternal(session) }
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
                updatePreparing()
            }
        }
    }

    /** The last link shared to SwipeTo, and what was playing then. */
    @Volatile private var shared: Shared? = null

    /** Another app plays [session] (or nothing): find what it is, so a swipe can send it. */
    private suspend fun updateExternal(session: SessionSnapshot?) {
        val resolved = session?.let { s ->
            (appContent(s, shared) ?: withContext(Dispatchers.IO) { mediaResolver.resolve(s) })?.let { Source(it, ExternalPlayback(mediaWatcher, s.packageName), s.packageName) }
        }
        if (resolved?.item != _external.value?.item || resolved?.app != _external.value?.app) {
            EventLog.i(
                when {
                    session == null -> "no other app playing"
                    resolved != null -> "${session.appLabel} plays ${resolved.item.title}: ${resolved.item.app?.let { "${it.service} ${it.contentId?.let { id -> "id $id" } ?: it.url.ifEmpty { "by title, channel ${it.channel}" }}" } ?: resolved.item.uri}"
                    else -> "${session.appLabel} plays something SwipeTo can't identify (title=${session.title})"
                },
            )
        }
        watchHandedFromApp()
        _external.value = resolved
        _unresolved.value = session?.takeIf { resolved == null }
        warmLinks()
        updateAiming()
        updatePreparing()
    }

    /** The screen used last, waiting a moment for discovery when SwipeTo has just started (for a share). */
    private suspend fun screenSoon(): LinkTarget? = screenToPrepare()
        ?: withTimeoutOrNull(SHARE_DISCOVERY_MS) { discovery.receivers.first { screenToPrepare() != null }; screenToPrepare() }

    /**
     * Share → SwipeTo of photos or videos (Gallery...): Gallery never says which photo is open, but a
     * share names exactly that one. One item comes with its neighbours in Gallery's order, so left and
     * right on the screen page on from it; several shared items are the album themselves.
     */
    suspend fun onMediaShared(uris: List<Uri>): String {
        val (items, index) = withContext(Dispatchers.IO) {
            val single = uris.singleOrNull()?.let(::mediaStoreId)?.let { recentMedia.around(it) }
            single ?: (uris.mapNotNull(::describeShared) to 0)
        }
        if (items.isEmpty()) return "SwipeTo: couldn't open that. Share a photo or video saved on this phone."
        EventLog.i("shared ${uris.size} item(s): ${items[index].kind} ${items[index].title} (${index + 1} of ${items.size})")
        val remote = link.value as? LinkState.Remote
        if (remote != null) {
            // The screen shows something already: put this there instead.
            showLocally(Album(items, index), play = false)
            sentFromGallery = true
            return if (coordinator.show(sharedMedia(items[index]), Transition.NEXT)) "Showing on ${remote.receiverName}" else "SwipeTo: ${remote.receiverName} didn't take it."
        }
        val target = screenSoon() ?: run {
            showLocally(Album(items, index), play = false)
            return "SwipeTo: no paired screen nearby. Open SwipeTo to pair one."
        }
        lastTarget = target
        return if (sendFromGallery(items, index)) "Sent to ${target.name}" else "SwipeTo: ${target.name} didn't take it."
    }

    /** The MediaStore id behind a shared uri (Gallery shares media uris; documents map to one on Android 11+). */
    private fun mediaStoreId(uri: Uri): Long? = runCatching {
        when {
            uri.authority == android.provider.MediaStore.AUTHORITY -> android.content.ContentUris.parseId(uri)
            Build.VERSION.SDK_INT >= 30 -> android.provider.MediaStore.getMediaUri(getApplication(), uri)?.let(android.content.ContentUris::parseId)
            else -> null
        }
    }.getOrNull()?.takeIf { it >= 0 }

    /**
     * A shared item outside the media library: copied into SwipeTo's cache while the share's read
     * grant lasts (it ends with the share), so the screen can still fetch it afterwards.
     */
    private fun describeShared(uri: Uri): PickedItem? {
        mediaStoreId(uri)?.let { return describe(uri) }
        val app = getApplication<Application>()
        val type = app.contentResolver.getType(uri) ?: return null
        val name = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "shared"
        val copy = java.io.File(app.cacheDir, "shared").apply { mkdirs() }.resolve("${System.nanoTime()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        val ok = runCatching {
            app.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } } != null
        }.getOrDefault(false)
        if (!ok) return null
        return describe(Uri.fromFile(copy))?.copy(mime = type)
    }

    /**
     * Share → SwipeTo: remembers [link] for what is playing now and sends it to the screen used last
     * (at the app's position). With no screen around it waits for the next lift and swipe. Returns
     * what to tell the user.
     */
    suspend fun onLinkShared(sharedLink: SharedLink, subject: String?): String {
        val playing = mediaWatcher.playing.value
        shared = Shared(sharedLink, playing?.packageName, playing?.title, subject)
        EventLog.i("shared ${sharedLink.service} link ${sharedLink.url} while ${playing?.appLabel ?: "nothing"} played")
        updateExternal(playing)
        if (link.value !is LinkState.Idle) return "SwipeTo: a screen is already playing. Bring it back first."
        val target = screenSoon() ?: return "SwipeTo: got it. Lift the phone toward a screen and swipe up to send."
        val receiver = discovery.receivers.value.firstOrNull { it.id == target.id } ?: return "SwipeTo: ${target.name} is not reachable."
        val source = _external.value ?: Source(sharedOnly(shared!!), NoPlayback)
        handedFrom = source.playback
        handedFromAppPaused = false
        sentFromGallery = false
        val sent = coordinator.sendTo(Target(receiver.id, receiver.name, receiver.webSocketUrl, receiver.host), sharedMedia(source.item), links.take(receiver.id))
        if (!sent) return "SwipeTo: ${receiver.name} didn't take it."
        trust.markUsed(receiver.id)
        _haptics.tryEmit(Haptic.SENT)
        return "Sent to ${receiver.name}"
    }
    /**
     * What a swipe sends: SwipeTo's own media while it is in front, else what another app plays
     * (when it could be identified).
     */
    fun source(): Source? = if (foreground) _media.value?.let { Source(it, inAppPlayback) } else _external.value

    /** SwipeTo's own screen is in front (it shows notices itself). */
    val inFront: Boolean get() = foreground

    /** Sensors, links and preparing run while SwipeTo is in front, another app plays something sendable, or a gallery is open. */
    private val active: Boolean get() = foreground || _external.value != null || _galleryOpen.value || shareHint() != null

    /** A video app plays something only a share can name: a raise shows how (the bubble's hint). */
    fun shareHint(): SessionSnapshot? = _unresolved.value?.takeIf { _external.value == null && it.packageName in SHAREABLE_APPS }

    /** From the background service: the app in front changed. */
    fun setForegroundApp(packageName: String?) {
        val gallery = !foreground && packageName in ForegroundApp.GALLERIES
        if (gallery == _galleryOpen.value) return
        _galleryOpen.value = gallery
        EventLog.i(if (gallery) "gallery in front: $packageName" else "gallery closed")
        warmLinks()
        updateAiming()
    }

    /**
     * A photo or video picked on the bubble over Gallery: sends [items]`[index]` to the screen the
     * phone was aimed at; the rest page with left/right swipes. A video Gallery is playing inline
     * starts where Gallery is.
     */
    suspend fun sendFromGallery(items: List<PickedItem>, index: Int): Boolean {
        if (link.value !is LinkState.Idle || index !in items.indices) return false
        val target = (aimState.value as? AimState.Targeted)?.target ?: lastTarget ?: screenToPrepare() ?: run {
            _hints.tryEmit("No paired screen nearby.")
            return false
        }
        val receiver = discovery.receivers.value.firstOrNull { it.id == target.id } ?: return false
        val item = items[index]
        val conn = if (aimState.value is AimState.Targeted) aim.takeTarget()?.second else links.take(receiver.id)
        showLocally(Album(items, index), play = false)
        val inline = _unresolved.value?.takeIf { item.kind == MediaKind.VIDEO && it.packageName in ForegroundApp.GALLERIES }
        handedFrom = inline?.let { ExternalPlayback(mediaWatcher, it.packageName) } ?: inAppPlayback
        handedFromAppPaused = false
        sentFromGallery = true
        EventLog.i("gallery pick: ${item.kind} ${item.title} (${index + 1} of ${items.size}) to ${receiver.name}")
        val sent = coordinator.sendTo(Target(receiver.id, receiver.name, receiver.webSocketUrl, receiver.host), sharedMedia(item), conn)
        if (sent) {
            trust.markUsed(receiver.id)
            _haptics.tryEmit(Haptic.SENT)
        }
        return sent
    }

    /** From the activity: sensors, camera and standby links run only while the app is in front. */
    fun setForeground(value: Boolean) {
        if (value) {
            // Coming back from Android's access screen picks up a fresh grant.
            _mediaAccess.value = mediaWatcher.hasAccess
            mediaWatcher.start()
        }
        foreground = value
        EventLog.i(if (value) "app in front" else "app in background")
        if (value) {
            _needsCameraPermission.value = settings.cameraTargeting.value && !scanner.hasPermission
            warmLinks()
        } else if (!active) {
            viewModelScope.launch { links.closeAll() }
        }
        if (!value) startBackgroundService()
        updateAiming()
        updatePreparing()
    }

    /** Watching other apps and the background service, from wherever SwipeTo was started (not only its screen). */
    fun startInBackground() {
        _mediaAccess.value = mediaWatcher.hasAccess
        mediaWatcher.start()
        if (!foreground) startBackgroundService()
    }

    /**
     * Keeps SwipeTo alive while other apps are in front (some Android builds freeze background apps within a
     * minute, seen on the A15), so it can follow what they play and react to a raise.
     */
    private fun startBackgroundService() {
        if (!mediaWatcher.hasAccess || !settings.otherApps.value) return
        val context = getApplication<Application>()
        runCatching { context.startForegroundService(android.content.Intent(context, SwipeToService::class.java)) }
            .onFailure { EventLog.i("background service not started: $it") }
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
        Swipe.LEFT -> page(+1)
        Swipe.RIGHT -> page(-1)
    }

    /** Album paging: on the phone, or on the screen (with a slide) while it has the album. */
    /** Next (+1) or previous (-1) in the album, from the notification or lock screen. */
    fun pageRemote(step: Int) {
        viewModelScope.launch { page(step) }
    }

    private suspend fun page(step: Int): Boolean {
        val album = _album.value?.takeIf { it.isAlbum } ?: return false
        val index = album.index + step
        if (index !in album.items.indices) {
            _hints.tryEmit(if (step > 0) "That was the last one." else "This is the first one.")
            return false
        }
        val next = album.items[index]
        if (link.value is LinkState.Remote) {
            val shown = coordinator.show(sharedMedia(next), if (step > 0) Transition.NEXT else Transition.PREVIOUS)
            if (!shown) return false
            showLocally(album.copy(index = index), play = false)
        } else {
            showLocally(album.copy(index = index), play = true)
        }
        return true
    }

    /** Debug overlay: behave as if the phone had just been raised (or lowered). */
    fun simulateAim(start: Boolean) {
        headingAtRaise = heading
        if (start) aim.onAimStart() else aim.endNow()
    }

    /** The other app's player was seen paused after the handoff, so its playing again is the user's doing. */
    private var handedFromAppPaused = false

    /**
     * After a swipe from another app the screen plays and that app is paused. If the user then plays
     * it on the phone again (seen on the A15: they pressed play in the system video player to control
     * the laptop), they want to watch here: bring it back, at the screen's position.
     */
    private fun watchHandedFromApp() {
        val from = handedFrom as? ExternalPlayback ?: return
        val remote = link.value as? LinkState.Remote ?: return
        if (remote.starting || remote.reconnecting) return
        val session = mediaWatcher.session(from.packageName)
        if (session?.playing != true) {
            handedFromAppPaused = true
        } else if (handedFromAppPaused) {
            handedFromAppPaused = false
            EventLog.i("${session.appLabel} plays again on the phone: bringing it back")
            bringBack()
        }
    }

    /** Swipe up on the bubble over another app (or on SwipeTo's own video). */
    suspend fun swipeUpFromAnywhere(): Boolean = swipeUp()

    private suspend fun swipeUp(): Boolean {
        val source = source() ?: return false
        val picked = source.item
        EventLog.i("swipe up while ${aimState.value::class.simpleName}")
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
            EventLog.i("swipe up: the aimed connection is gone")
            _hints.tryEmit("Lost the screen. Raise the phone again.")
            return false
        }
        val receiver = discovery.receivers.value.firstOrNull { it.id == target.id } ?: run {
            EventLog.i("swipe up: ${target.name} is no longer discovered")
            conn.close()
            return false
        }
        EventLog.i("swipe up: handing off ${picked.title} to ${target.name}${source.app?.let { " from $it" } ?: ""}")
        handedFrom = source.playback
        handedFromAppPaused = false
        sentFromGallery = false
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
        val wanted = active && settings.raiseToAim.value && link.value is LinkState.Idle && orientation.available
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
                }
                when (detector.onSample(sample)) {
                    AimPhase.AIMING -> {
                        headingAtRaise = sample.headingDeg
                        EventLog.i("raise detected at %.1f°, heading %s".format(sample.elevationDeg, sample.headingDeg?.let { "%.0f°".format(it) } ?: "unknown"))
                        if (source() != null || _galleryOpen.value || shareHint() != null) aim.onAimStart()
                    }
                    AimPhase.IDLE -> {
                        EventLog.i("lowered at %.1f°".format(sample.elevationDeg))
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
        EventLog.i("learned direction of $receiverId: %.0f° (%d samples)".format(learned.headingDeg, learned.samples))
    }

    private fun bearingsText(): String = pairedTargets().mapNotNull { t ->
        bearings.get(t.id)?.let { "${t.name} %.0f° (%d)".format(it.headingDeg, it.samples) }
    }.joinToString().ifEmpty { "none learned" }

    private fun pairedTargets(): List<LinkTarget> =
        discovery.receivers.value.filter { trust.isPaired(it.id) }.map { LinkTarget(it.id, it.name, it.webSocketUrl) }

    private fun warmLinks() {
        if (!active || link.value !is LinkState.Idle || (source() == null && !_galleryOpen.value && shareHint() == null)) return
        viewModelScope.launch { links.warm(pairedTargets()) }
    }

    /** The same [ContentMediaFile] per item, so the publisher hands out the same URL (prefetch, then handoff). */
    private fun sharedMedia(picked: PickedItem): SharedMedia {
        picked.app?.let { return SharedMedia(null, MediaKind.APP, picked.title, picked.durationMs, it) }
        val file = files.getOrPut(picked.uri) { ContentMediaFile(getApplication<Application>().contentResolver, picked.uri, picked.mime, picked.size) }
        val duration = if (picked.kind == MediaKind.VIDEO) picked.durationMs ?: player.duration.takeIf { it > 0 } else null
        return SharedMedia(file, picked.kind, picked.title, duration)
    }

    /** What a swipe toward [target] would send, published for it, at the phone's position. Videos only. */
    private suspend fun prefetchFor(target: LinkTarget): Prefetch? {
        val source = source()?.takeIf { it.item.kind == MediaKind.VIDEO } ?: return null
        val item = source.item
        val receiver = discovery.receivers.value.firstOrNull { it.id == target.id } ?: return null
        val shared = sharedMedia(item)
        val file = shared.file ?: return null
        val url = publisher.publish(file, receiver.host)
        return Prefetch(MediaInfo(MediaKind.VIDEO, url, file.mime, shared.title, shared.durationMs), source.playback.positionMs)
    }

    fun onMediaPicked(uris: List<Uri>) = viewModelScope.launch {
        val picked = withContext(Dispatchers.IO) { uris.mapNotNull(::describe) }
        if (picked.isEmpty()) {
            _hints.tryEmit("Couldn't open that. Pick a video or photo saved on this phone.")
            return@launch
        }
        if (link.value !is LinkState.Idle) coordinator.bringBack()
        files.clear()
        showLocally(Album(picked, 0), play = true)
        warmLinks()
        updateAiming()
    }

    /** Makes [album]'s current item the one on the phone: a video in the player, or a decoded photo. */
    private suspend fun showLocally(album: Album, play: Boolean) {
        _album.value = album
        val item = album.current
        _media.value = item
        if (item.kind == MediaKind.VIDEO) {
            _photo.value = null
            player.setMediaItem(MediaItem.fromUri(item.uri))
            player.prepare()
            player.playWhenReady = play
        } else {
            player.stop()
            player.clearMediaItems()
            _photo.value = withContext(Dispatchers.IO) { decodePhoto(item.uri) }
        }
    }

    private fun decodePhoto(uri: Uri): Bitmap? = runCatching {
        val resolver = getApplication<Application>().contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF rotation, so portrait photos stand up.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (longest / (sample * 2) >= PHOTO_MAX_PX) sample *= 2
                decoder.setTargetSampleSize(sample)
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_MAX_PX) sample *= 2
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    }.getOrNull()

    fun sendTo(receiver: DiscoveredReceiver) = viewModelScope.launch {
        val picked = _media.value ?: return@launch
        if (!onLocalNetwork()) {
            _hints.tryEmit("Connect this phone to the same Wi-Fi as ${receiver.name}.")
            return@launch
        }
        // A standby link to this screen would refuse a second hello; use it instead of a new connection.
        val ready = links.take(receiver.id)
        handedFrom = inAppPlayback
        sentFromGallery = false
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

    fun setRemotePlaying(play: Boolean) = coordinator.setPlaying(play)

    fun stopOnScreen() = coordinator.stopOnScreen()

    fun skip(deltaMs: Long) {
        (link.value as? LinkState.Remote)?.let { coordinator.seekTo((it.positionMs + deltaMs).coerceAtLeast(0)) }
    }

    fun seekTo(positionMs: Long) = coordinator.seekTo(positionMs)

    fun bringBack() = viewModelScope.launch {
        coordinator.bringBack()
        _haptics.tryEmit(Haptic.BROUGHT_BACK)
    }

    /** A WebSocket to a screen, over the Wi-Fi network; fails at once while Wi-Fi is off. */
    private suspend fun connectToScreen(url: String): SenderConnection {
        val network = wifi
        if (network == null && wifiSeen) throw ReceiverException("This phone is not on Wi-Fi")
        val client = network?.let { net ->
            wifiClient?.takeIf { it.first == net }?.second
                ?: SenderConnection.defaultClient.newBuilder().socketFactory(net.socketFactory).build().also { wifiClient = net to it }
        } ?: SenderConnection.defaultClient
        return SenderConnection.connect(url, trust.identity, client, log = { EventLog.i(it) })
    }

    /** Preparing runs while the app is in front and the phone has the media; each tick checks the rest. */
    private fun updatePreparing() {
        if (active && link.value is LinkState.Idle) {
            preparer.start()
        } else {
            val wasPreparing = preparer.active
            preparer.stop()
            // Gone to the background: what was published for preparing stops being served.
            if (wasPreparing && link.value is LinkState.Idle) publisher.endSession()
        }
    }

    /** The screen used most recently, if it is paired and on this Wi-Fi now. */
    private fun screenToPrepare(): LinkTarget? = pairedTargets()
        .mapNotNull { t -> trust.lastUsed(t.id)?.let { t to it } }
        .maxByOrNull { it.second }?.first

    private fun onUnmeteredWifi(): Boolean {
        val network = wifi ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    private fun onLocalNetwork(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun finishOnboarding() = settings.setOnboarded(true)

    private fun refreshPaired() {
        _receivers.value = _receivers.value.map { it.copy(paired = trust.isPaired(it.receiver.id)) }
    }

    private fun describe(uri: Uri): PickedItem? {
        val resolver = getApplication<Application>().contentResolver
        val type = resolver.getType(uri)
        val kind = if (type?.startsWith("image/") == true) MediaKind.IMAGE else MediaKind.VIDEO
        val fallbackTitle = if (kind == MediaKind.IMAGE) "Photo" else "Video"
        var title = fallbackTitle
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { title = it.substringBeforeLast('.') }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        if (size < 0) size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: return null
        if (kind == MediaKind.IMAGE) {
            if (title.all(Char::isDigit)) title = fallbackTitle
            return PickedItem(uri, title.take(200), type ?: "image/jpeg", size, null, kind)
        }
        val mime = type?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        val (duration, embeddedTitle) = runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(getApplication(), uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() to
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            }
        }.getOrDefault(null to null)
        // The Photo Picker often names files by media-store id ("1000033915"), which means nothing to anyone.
        if (title.all(Char::isDigit)) title = embeddedTitle?.takeIf { it.isNotBlank() } ?: "Video"
        return PickedItem(uri, title.take(200), mime, size, duration, kind)
    }

    override fun onCleared() {
        mediaWatcher.stop()
        preparer.stop()
        stopAiming()
        coordinator.takeBackNow()
        connectivity.unregisterNetworkCallback(networkCallback)
        discovery.stop()
        server.stop()
        player.release()
    }
}
