package dev.swipeto.sender.media

import fi.iki.elonen.NanoHTTPD
import java.io.FilterInputStream
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Something the phone can serve: a picked video, a test file. */
interface MediaFile {
    val size: Long
    val mime: String

    /** A stream positioned at [offset]; the caller closes it. */
    fun open(offset: Long): InputStream
}

/**
 * The phone's local media server (docs/protocol.md, section 5.4). It serves only files explicitly
 * handed off in this session, at `/m/<sessionToken>/<fileId>`, with HTTP Range support. Any other
 * path, an old token, or an unknown file id is a plain 404 that does not say which part was wrong.
 */
class LocalMediaServer(port: Int = 0) : NanoHTTPD(port) {
    private val files = ConcurrentHashMap<String, MediaFile>()

    @Volatile
    var sessionToken: String = newToken(16)
        private set

    /** Starts listening if not already; returns the port. */
    fun ensureStarted(): Int {
        if (!isAlive) start(SOCKET_READ_TIMEOUT, true)
        return listeningPort
    }

    /** Makes [file] downloadable for this session; returns its URL as reachable at [localAddress]. */
    fun publish(file: MediaFile, localAddress: InetAddress): String {
        val port = ensureStarted()
        val fileId = newToken(9)
        files[fileId] = file
        val host = if (localAddress is Inet6Address) "[${localAddress.hostAddress?.substringBefore('%')}]" else localAddress.hostAddress
        return "http://$host:$port/m/$sessionToken/$fileId"
    }

    /** Forgets every file and rotates the token so old URLs stop working. */
    fun endSession() {
        files.clear()
        sessionToken = newToken(16)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET && session.method != Method.HEAD) return notFound()
        val parts = session.uri.trim('/').split('/')
        if (parts.size != 3 || parts[0] != "m" || !constantTimeEquals(parts[1], sessionToken)) return notFound()
        val file = files[parts[2]] ?: return notFound()

        val response = when (val range = RangeRequest.parse(session.headers["range"], file.size)) {
            RangeRequest.Full -> newFixedLengthResponse(Response.Status.OK, file.mime, file.open(0), file.size)
            is RangeRequest.Partial ->
                newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, file.mime, Limited(file.open(range.start), range.length), range.length)
                    .apply { addHeader("Content-Range", "bytes ${range.start}-${range.end}/${file.size}") }
            RangeRequest.Unsatisfiable ->
                newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    .apply { addHeader("Content-Range", "bytes */${file.size}") }
        }
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

    /** Stops a ranged body after [remaining] bytes even though the underlying stream goes on. */
    private class Limited(source: InputStream, private var remaining: Long) : FilterInputStream(source) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            return super.read().also { if (it >= 0) remaining-- }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun available() = minOf(super.available().toLong(), remaining).toInt()
    }

    private companion object {
        val random = SecureRandom()

        fun newToken(bytes: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

        fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
