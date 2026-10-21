package dev.swipeto.sender.media

import dev.swipeto.sender.handoff.MediaPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetAddress

/** Publishes through [LocalMediaServer], using the phone address that routes to the receiver. */
class ServerPublisher(private val server: LocalMediaServer) : MediaPublisher {
    override suspend fun publish(file: MediaFile, receiverHost: InetAddress): String = withContext(Dispatchers.IO) {
        server.publish(file, localAddressFor(receiverHost))
    }

    override fun endSession() = server.endSession()

    companion object {
        /** The local address the OS would use to reach [remote]. A UDP "connect" sends no packets. */
        fun localAddressFor(remote: InetAddress): InetAddress = DatagramSocket().use { probe ->
            probe.connect(remote, 9)
            probe.localAddress
        }
    }
}
