package dev.swipeto.sender

import android.app.Application
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
import dev.swipeto.protocol.MediaKind
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PickedVideo(val uri: Uri, val title: String, val mime: String, val size: Long, val durationMs: Long?)

data class ReceiverItem(val receiver: DiscoveredReceiver, val paired: Boolean)

data class PairingRequest(val receiverName: String, val attempt: Int, val reply: CompletableDeferred<String?>)

/** Owns the phone player, discovery, the media server and the handoff coordinator; survives rotation. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    private val trust = TrustStore(app)
    private val discovery = ReceiverDiscovery(app)
    private val server = LocalMediaServer()
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

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
        connect = { url -> SenderConnection.connect(url, trust.identity) },
    )

    val link: StateFlow<LinkState> = coordinator.state
    val notices = coordinator.notices

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
            discovery.receivers.collect { list -> _receivers.value = list.map { ReceiverItem(it, trust.isPaired(it.id)) } }
        }
    }

    fun onVideoPicked(uri: Uri) = viewModelScope.launch {
        val picked = withContext(Dispatchers.IO) { describe(uri) } ?: return@launch
        if (link.value !is LinkState.Idle) coordinator.bringBack()
        _video.value = picked
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
    }

    fun sendTo(receiver: DiscoveredReceiver) = viewModelScope.launch {
        val picked = _video.value ?: return@launch
        val file = ContentMediaFile(getApplication<Application>().contentResolver, picked.uri, picked.mime, picked.size)
        val sent = coordinator.sendTo(
            Target(receiver.id, receiver.name, receiver.webSocketUrl, receiver.host),
            SharedMedia(file, MediaKind.VIDEO, picked.title, picked.durationMs ?: player.duration.takeIf { it > 0 }),
        )
        if (sent) refreshPaired()
    }

    fun submitCode(code: String) {
        _pairing.value?.reply?.complete(code)
    }

    fun cancelPairing() {
        _pairing.value?.reply?.complete(null)
    }

    fun togglePlay() = coordinator.togglePlay()

    fun seekTo(positionMs: Long) = coordinator.seekTo(positionMs)

    fun bringBack() = viewModelScope.launch { coordinator.bringBack() }

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
        val duration = runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(getApplication(), uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull()
        return PickedVideo(uri, title.take(200), mime, size, duration)
    }

    override fun onCleared() {
        coordinator.onConnectionLost()
        connectivity.unregisterNetworkCallback(networkCallback)
        discovery.stop()
        server.stop()
        player.release()
    }
}
