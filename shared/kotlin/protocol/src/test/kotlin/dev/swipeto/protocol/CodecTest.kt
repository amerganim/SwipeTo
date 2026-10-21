package dev.swipeto.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodecTest {
    private val id = "3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b"

    @Test
    fun `new messages get a lowercase uuid and current time`() {
        val before = System.currentTimeMillis()
        val ping = Ping()

        assertTrue(Regex("^[0-9a-f-]{36}$").matches(ping.id))
        assertTrue(ping.ts in before..before + 5000)
    }

    @Test
    fun `serialized messages use camelCase and omit absent optionals`() {
        val json = MessageCodec.serialize(Control(ControlAction.PAUSE, id = id, ts = 1))

        assertEquals("""{"type":"control","id":"$id","ts":1,"action":"pause"}""", json)
    }

    @Test
    fun `serialization matches the dotnet library byte for byte`() {
        // Same message and expected text as the .NET CodecTests, so both libraries emit identical JSON.
        val json = MessageCodec.serialize(Handoff(MediaInfo(MediaKind.VIDEO, "http://h/m/t/f", "video/mp4", "t", 5), 10, true, id = id, ts = 1))

        assertEquals(
            """{"type":"handoff","id":"$id","ts":1,"media":{"kind":"video","url":"http://h/m/t/f","mime":"video/mp4","title":"t","durationMs":5},"positionMs":10,"playing":true}""",
            json,
        )
    }

    @Test
    fun `malformed input is bad_message`() {
        for (json in listOf("not json", """{"type":"ping","id":"$id","ts":1.5}""", """{"type":"ping","id":"$id"}""", """{"type":"ping","id":"$id","ts":1,"x":null}""")) {
            assertEquals(ErrorCode.BAD_MESSAGE, assertFailsWith<ProtocolException>(json) { MessageCodec.parse(json) }.code)
        }
    }

    @Test
    fun `rejection carries the message id for replyTo`() {
        val error = assertFailsWith<ProtocolException> { MessageCodec.parse("""{"type":"control","id":"$id","ts":1,"action":"seek"}""") }

        assertEquals(id, error.toErrorMessage().replyTo)
    }

    @Test
    fun `oversized messages are rejected`() {
        val json = """{"type":"error","id":"$id","ts":1,"code":"busy","message":"${"x".repeat(70_000)}"}"""

        assertFailsWith<ProtocolException> { MessageCodec.parse(json) }
    }

    @Test
    fun `name length counts code points`() {
        val name = "😀".repeat(64)
        val json = MessageCodec.serialize(PairRequest("a1b2c3d4-e5f6-4789-8abc-def012345678", name))

        assertEquals(name, (MessageCodec.parse(json) as PairRequest).senderName)
    }

    @Test
    fun `wire enum names are snake_case`() {
        assertEquals("unsupported_media", ErrorCode.UNSUPPORTED_MEDIA.wire)
        assertEquals(ErrorCode.VERSION_MISMATCH, wireValueOf<ErrorCode>("version_mismatch"))
        assertEquals(null, wireValueOf<ErrorCode>("VERSION_MISMATCH"))
    }
}
