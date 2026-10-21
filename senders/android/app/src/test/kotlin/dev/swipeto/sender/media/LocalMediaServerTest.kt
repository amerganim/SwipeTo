package dev.swipeto.sender.media

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.random.Random

class LocalMediaServerTest {
    @TempDir
    lateinit var dir: File

    private val server = LocalMediaServer()
    private val data = Random(7).nextBytes(10_000)
    private lateinit var url: String

    @BeforeEach
    fun setUp() {
        val file = File(dir, "clip.mp4").apply { writeBytes(data) }
        url = server.publish(LocalFile(file, "video/mp4"), InetAddress.getLoopbackAddress())
    }

    @AfterEach
    fun tearDown() = server.stop()

    private fun get(url: String, range: String? = null, method: String = "GET"): Triple<Int, Map<String, String>, ByteArray> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        range?.let { connection.setRequestProperty("Range", it) }
        val code = connection.responseCode
        val body = (if (code < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.first() }.mapKeys { it.key.lowercase() }
        return Triple(code, headers, body)
    }

    @Test
    fun `url has a session token and a file id`() {
        assertEquals(Regex("^http://127\\.0\\.0\\.1:\\d+/m/[A-Za-z0-9_-]{22}/[A-Za-z0-9_-]{12}$").matches(url), true, url)
    }

    @Test
    fun `whole file with 200 and accept-ranges`() {
        val (code, headers, body) = get(url)

        assertEquals(200, code)
        assertEquals("bytes", headers["accept-ranges"])
        assertEquals("video/mp4", headers["content-type"])
        assertArrayEquals(data, body)
    }

    @Test
    fun `range request returns 206 with exact bytes`() {
        val (code, headers, body) = get(url, "bytes=5000-5009")

        assertEquals(206, code)
        assertEquals("bytes 5000-5009/10000", headers["content-range"])
        assertEquals("10", headers["content-length"])
        assertArrayEquals(data.copyOfRange(5000, 5010), body)
    }

    @Test
    fun `open-ended and suffix ranges`() {
        assertArrayEquals(data.copyOfRange(9990, 10_000), get(url, "bytes=9990-").third)
        assertArrayEquals(data.copyOfRange(9900, 10_000), get(url, "bytes=-100").third)
    }

    @Test
    fun `range past the end is 416`() {
        val (code, headers, _) = get(url, "bytes=10000-")

        assertEquals(416, code)
        assertEquals("bytes */10000", headers["content-range"])
    }

    @Test
    fun `head has headers and no body`() {
        val (code, headers, body) = get(url, method = "HEAD")

        assertEquals(200, code)
        assertEquals("10000", headers["content-length"])
        assertEquals(0, body.size)
    }

    @Test
    fun `wrong token, unknown file and other paths are plain 404s`() {
        val parts = url.split('/')
        val base = parts.take(3).joinToString("/")
        assertEquals(404, get("$base/m/AAAAAAAAAAAAAAAAAAAAAA/${parts.last()}").first)
        assertEquals(404, get("$base/m/${parts[4]}/nope").first)
        assertEquals(404, get("$base/").first)
        assertEquals(404, get("$base/m/${parts[4]}").first)
    }

    @Test
    fun `ending the session revokes old urls and rotates the token`() {
        val oldToken = server.sessionToken

        server.endSession()

        assertEquals(404, get(url).first)
        assertNotEquals(oldToken, server.sessionToken)
    }
}
