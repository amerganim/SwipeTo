package dev.swipeto.sender.net

import dev.swipeto.protocol.ControlAction
import dev.swipeto.protocol.ErrorMessage
import dev.swipeto.protocol.Handoff
import dev.swipeto.protocol.HandoffAck
import dev.swipeto.protocol.Hello
import dev.swipeto.protocol.HelloAck
import dev.swipeto.protocol.MediaInfo
import dev.swipeto.protocol.Message
import dev.swipeto.protocol.MessageCodec
import dev.swipeto.protocol.PairConfirm
import dev.swipeto.protocol.PairFailure
import dev.swipeto.protocol.PairRequest
import dev.swipeto.protocol.PairResult
import dev.swipeto.protocol.Ping
import dev.swipeto.protocol.PlaybackState
import dev.swipeto.protocol.Pong
import dev.swipeto.protocol.ProtocolException
import dev.swipeto.protocol.ProtocolSession
import dev.swipeto.protocol.ProtocolVersion
import dev.swipeto.protocol.Pullback
import dev.swipeto.protocol.PullbackRequest
import dev.swipeto.protocol.Role
import dev.swipeto.protocol.SessionState
import dev.swipeto.protocol.AimEnd
import dev.swipeto.protocol.AimStart
import dev.swipeto.protocol.Control
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SenderIdentity(val senderId: String, val senderName: String)

/** Thrown when the receiver refuses or the connection is gone. */
class ReceiverException(message: String, val code: dev.swipeto.protocol.ErrorCode? = null) : IOException(message)

/**
 * One session with one receiver over an OkHttp WebSocket (docs/protocol.md). Every message goes
 * through the shared [ProtocolSession] state machine in both directions. Requests are matched to
 * responses by `replyTo`; `state` and stray `error` messages come out of [events].
 *
 * Keepalive per section 5.7: answers `ping`, sends `ping` after 5 s of quiet, and gives up after
 * 15 s without hearing anything ([closed] becomes true).
 */
class SenderConnection private constructor(
    private val identity: SenderIdentity,
    private val clock: () -> Long,
) {
    private val session = ProtocolSession(Role.SENDER)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Message>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<Message>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _closed = MutableStateFlow(false)
    private val _lastState = MutableStateFlow<PlaybackState?>(null)
    private lateinit var socket: WebSocket

    @Volatile private var lastSent = clock()
    @Volatile private var lastReceived = clock()

    /** `state` and unsolicited `error` messages from the receiver. */
    val events: SharedFlow<Message> = _events.asSharedFlow()

    /** Latest `state` from the receiver. */
    val lastState: StateFlow<PlaybackState?> = _lastState.asStateFlow()

    /** True once the connection is gone, for whatever reason. */
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    val state: SessionState get() = synchronized(session) { session.state }

    /** Pairs: sends `pair_request`, then asks for the code (attempt 1, 2, …) until it works or fails for good. */
    suspend fun pair(askForCode: suspend (attempt: Int) -> String?, maxAttempts: Int = 3): String? {
        send(PairRequest(identity.senderId, identity.senderName))
        for (attempt in 1..maxAttempts) {
            val code = askForCode(attempt) ?: return null
            val result = request<PairResult>(PairConfirm(code))
            if (result.ok) return result.trustToken
            if (result.reason != PairFailure.WRONG_CODE) return null
        }
        return null
    }

    suspend fun hello(trustToken: String): HelloAck =
        request(Hello(identity.senderId, identity.senderName, trustToken, ProtocolVersion.CURRENT))

    fun aimStart() = send(AimStart())

    fun aimEnd() = send(AimEnd())

    suspend fun handoff(media: MediaInfo, positionMs: Long, playing: Boolean): HandoffAck = request(Handoff(media, positionMs, playing))

    fun control(action: ControlAction, positionMs: Long? = null) = send(Control(action, positionMs))

    suspend fun pullback(): Pullback = request(PullbackRequest())

    /** Checks [message] against the state machine and sends it. */
    fun send(message: Message) {
        synchronized(session) {
            session.outgoing(message)
            sendRaw(message)
        }
    }

    suspend fun <T : Message> request(message: Message, timeoutMs: Long = 10_000): T {
        val reply = CompletableDeferred<Message>()
        pending[message.id] = reply
        try {
            send(message)
            @Suppress("UNCHECKED_CAST")
            return withTimeout(timeoutMs) { reply.await() } as? T
                ?: throw ReceiverException("Unexpected reply to ${message.type}")
        } finally {
            pending.remove(message.id)
        }
    }

    fun close() {
        if (_closed.value) return
        socket.close(1000, "bye")
        finish()
    }

    private fun sendRaw(message: Message) {
        if (socket.send(MessageCodec.serialize(message))) lastSent = clock()
    }

    private fun onText(text: String) {
        lastReceived = clock()
        val message = try {
            MessageCodec.parse(text).also { synchronized(session) { session.incoming(it) } }
        } catch (e: ProtocolException) {
            sendRaw(e.toErrorMessage())
            return
        }
        val replyTo = message.replyTo
        val waiter = replyTo?.let(pending::get)
        when {
            message is Ping -> sendRaw(Pong(replyTo = message.id))
            message is Pong -> Unit
            waiter != null && message is ErrorMessage ->
                waiter.completeExceptionally(ReceiverException("${message.code.wire}: ${message.description}", message.code))
            waiter != null -> waiter.complete(message)
            else -> {
                if (message is PlaybackState) _lastState.value = message
                _events.tryEmit(message)
            }
        }
    }

    private fun finish() {
        if (_closed.getAndSet(true)) return
        pending.values.forEach { it.completeExceptionally(ReceiverException("Connection closed")) }
        scope.cancel()
    }

    private fun startKeepalive() = scope.launch {
        while (isActive) {
            delay(500)
            val now = clock()
            if (now - lastReceived >= SILENCE_TIMEOUT_MS) {
                socket.cancel()
                finish()
            } else if (now - lastSent >= PING_INTERVAL_MS) {
                sendRaw(Ping())
            }
        }
    }

    private fun MutableStateFlow<Boolean>.getAndSet(value: Boolean): Boolean = synchronized(this) {
        val old = this.value
        this.value = value
        old
    }

    companion object {
        const val PING_INTERVAL_MS = 5_000L
        const val SILENCE_TIMEOUT_MS = 15_000L

        /** A client without OkHttp's own pings: the protocol has its own keepalive. */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()
        }

        /** Opens the WebSocket at [url] (`ws://host:port/v1`) and returns once it is open. */
        suspend fun connect(
            url: String,
            identity: SenderIdentity,
            client: OkHttpClient = defaultClient,
            clock: () -> Long = System::currentTimeMillis,
        ): SenderConnection {
            val connection = SenderConnection(identity, clock)
            val opened = CompletableDeferred<Unit>()
            connection.socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) = connection.onText(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    connection.finish()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = connection.finish()

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.completeExceptionally(ReceiverException("Cannot reach the screen: ${t.message}"))
                    connection.finish()
                }
            })
            try {
                withTimeout(6_000) { opened.await() }
            } catch (e: Exception) {
                connection.socket.cancel()
                connection.finish()
                throw e as? ReceiverException ?: ReceiverException("Cannot reach the screen: ${e.message}")
            }
            connection.startKeepalive()
            return connection
        }
    }
}
