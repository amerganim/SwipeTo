package dev.swipeto.sender.handoff

import dev.swipeto.protocol.ControlAction
import dev.swipeto.protocol.MediaInfo
import dev.swipeto.protocol.MediaKind
import dev.swipeto.sender.media.LocalFile
import dev.swipeto.sender.media.LocalMediaServer
import dev.swipeto.sender.net.SenderConnection
import dev.swipeto.sender.net.SenderIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.util.UUID
import kotlin.random.Random

/**
 * The Android sender's networking against the real .NET mock receiver (tools/mock-receiver), so the
 * Kotlin and C# protocol stacks are checked against each other over a real socket. Opt in:
 *
 *   dotnet run --project tools/mock-receiver -- --port 48650 --pair-code 314159 --no-mdns
 *   SWIPETO_MOCK_RECEIVER=ws://127.0.0.1:48650/v1 SWIPETO_MOCK_CODE=314159 ./gradlew :app:testDebugUnitTest
 */
@EnabledIfEnvironmentVariable(named = "SWIPETO_MOCK_RECEIVER", matches = "ws://.+")
class MockReceiverInteropTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `full flow against the dotnet mock receiver`() = runBlocking {
        val server = LocalMediaServer()
        val file = File(dir, "clip.mp4").apply { writeBytes(Random(1).nextBytes(3 * 1024 * 1024)) }
        val url = server.publish(LocalFile(file, "video/mp4"), InetAddress.getLoopbackAddress())
        val connection = SenderConnection.connect(System.getenv("SWIPETO_MOCK_RECEIVER"), SenderIdentity(UUID.randomUUID().toString(), "Kotlin test"))
        try {
            val token = connection.pair({ System.getenv("SWIPETO_MOCK_CODE") ?: "123456" })!!
            assertTrue(connection.hello(token).accepted)

            assertTrue(connection.handoff(MediaInfo(MediaKind.VIDEO, url, "video/mp4", "Interop", 600_000), 42_000, true).accepted)
            withTimeout(10_000) { connection.lastState.first { it?.playing == true } }

            connection.control(ControlAction.PAUSE)
            withTimeout(5_000) { connection.lastState.first { it?.playing == false } }
            connection.control(ControlAction.SEEK, 120_000)
            withTimeout(5_000) { connection.lastState.first { it?.positionMs == 120_000L } }
            connection.control(ControlAction.PLAY)
            withTimeout(5_000) { connection.lastState.first { it?.playing == true } }

            val back = connection.pullback()
            assertTrue(back.playing)
            assertTrue(back.positionMs in 120_000..125_000, "pull-back at ${back.positionMs}")
            assertEquals(dev.swipeto.protocol.SessionState.SESSION, connection.state)
        } finally {
            connection.close()
            server.stop()
        }
    }
}
