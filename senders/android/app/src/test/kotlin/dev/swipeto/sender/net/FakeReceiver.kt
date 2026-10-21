package dev.swipeto.sender.net

import dev.swipeto.protocol.Control
import dev.swipeto.protocol.ControlAction
import dev.swipeto.protocol.ErrorCode
import dev.swipeto.protocol.Handoff
import dev.swipeto.protocol.HandoffAck
import dev.swipeto.protocol.Hello
import dev.swipeto.protocol.HelloAck
import dev.swipeto.protocol.Message
import dev.swipeto.protocol.MessageCodec
import dev.swipeto.protocol.PairConfirm
import dev.swipeto.protocol.PairFailure
import dev.swipeto.protocol.PairRequest
import dev.swipeto.protocol.PairResult
import dev.swipeto.protocol.Ping
import dev.swipeto.protocol.PlaybackState
import dev.swipeto.protocol.Pong
import dev.swipeto.protocol.ProtocolSession
import dev.swipeto.protocol.Pullback
import dev.swipeto.protocol.PullbackRequest
import dev.swipeto.protocol.Role
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scripted receiver on MockWebServer: pairs with [code], accepts tokens it issued, accepts
 * handoffs (unless [busy]), tracks a position, answers control with `state` and pull-back with
 * `pullback`. Runs the real [ProtocolSession] on its side too, so out-of-state messages fail tests.
 */
class FakeReceiver(
    private val code: String = "123456",
    private val receiverId: String = "0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21",
    var busy: Boolean = false,
    var answerPings: Boolean = true,
) : AutoCloseable {
    private val server = MockWebServer()
    private var current: Listener? = null
    val issuedTokens = mutableSetOf<String>()
    val received = CopyOnWriteArrayList<Message>()
    var socket: WebSocket? = null
        private set
    var positionMs = 0L
        private set
    var playing = false
        private set
    var lastHandoff: Handoff? = null
        private set

    val url: String

    init {
        repeat(4) { server.enqueue(MockResponse.Builder().webSocketUpgrade(Listener()).build()) }
        server.start()
        url = server.url("/v1").toString().replaceFirst("http", "ws")
    }

    fun push(message: Message) = send(message)

    /** The viewer closed playback on the screen. */
    fun endPlayback() = send(PlaybackState(positionMs, playing = false, ended = true))

    /** The screen goes away. (Server-side sockets can't be cancel()ed; a close frame ends it just as surely.) */
    fun drop() {
        socket?.close(1001, "gone")
    }

    override fun close() {
        socket?.close(1001, "test over")
        server.close()
    }

    private fun send(message: Message) = current!!.send(message)

    /** One per connection, each with its own protocol state. */
    private inner class Listener : WebSocketListener() {
        private val session = ProtocolSession(Role.RECEIVER)
        private lateinit var webSocket: WebSocket

        fun send(message: Message) {
            synchronized(session) {
                session.outgoing(message)
                webSocket.send(MessageCodec.serialize(message))
            }
        }

        private fun reply(message: Message) = send(message)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            this.webSocket = webSocket
            socket = webSocket
            current = this
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = MessageCodec.parse(text)
            received += message
            if (message is Ping) {
                if (answerPings) webSocket.send(MessageCodec.serialize(Pong(replyTo = message.id)))
                return
            }
            if (message is Pong) return
            synchronized(session) { session.incoming(message) }
            when (message) {
                is PairRequest -> Unit
                is PairConfirm -> reply(
                    if (message.code == code) PairResult.success(token(), message.id) else PairResult.failure(PairFailure.WRONG_CODE, message.id),
                )
                is Hello -> {
                    val ok = message.trustToken in issuedTokens
                    reply(HelloAck(receiverId, ok, if (ok) null else ErrorCode.NOT_PAIRED, replyTo = message.id))
                }
                is Handoff -> {
                    if (busy) {
                        reply(HandoffAck(false, ErrorCode.BUSY, replyTo = message.id))
                    } else {
                        lastHandoff = message
                        positionMs = message.positionMs
                        playing = message.playing
                        reply(HandoffAck(true, replyTo = message.id))
                        reply(PlaybackState(positionMs, playing, false))
                    }
                }
                is Control -> {
                    when (message.action) {
                        ControlAction.PLAY -> playing = true
                        ControlAction.PAUSE -> playing = false
                        ControlAction.SEEK -> positionMs = message.positionMs!!
                        ControlAction.STOP -> playing = false
                    }
                    reply(PlaybackState(positionMs, playing, message.action == ControlAction.STOP))
                }
                is PullbackRequest -> reply(Pullback(positionMs, playing, replyTo = message.id))
                else -> Unit
            }
        }

        private fun token() = "tok_${issuedTokens.size}_abcdefghijklmnopqrstuv".also { issuedTokens += it }
    }
}
