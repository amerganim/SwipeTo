package dev.swipeto.sender.media

import dev.swipeto.sender.handoff.MediaPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Publishes through [LocalMediaServer], using the phone address that routes to the receiver. The
 * same file for the same receiver keeps its URL until [endSession], so a handoff after a prefetch
 * names exactly what the screen already started loading.
 */
class ServerPublisher(private val server: LocalMediaServer) : MediaPublisher {
    private val published = ConcurrentHashMap<Pair<MediaFile, InetAddress>, String>()

    override suspend fun publish(file: MediaFile, receiverHost: InetAddress): String = withContext(Dispatchers.IO) {
        published.getOrPut(file to receiverHost) { server.publish(file, localAddressFor(receiverHost)) }
    }

    override fun endSession() {
        published.clear()
        server.endSession()
    }

    companion object {
        /** The local address the OS would use to reach [remote]. A UDP "connect" sends no packets. */
        fun localAddressFor(remote: InetAddress): InetAddress = DatagramSocket().use { probe ->
            probe.connect(remote, 9)
            probe.localAddress
        }
    }
}
