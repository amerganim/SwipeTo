package dev.swipeto.protocol

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class TestVectorTest {
    private val root = File(System.getProperty("swipeto.vectors") ?: fail("swipeto.vectors system property not set"))

    private fun vectors(kind: String) =
        File(root, kind).listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }.also { assertTrue(it.isNotEmpty()) }

    @Test
    fun `valid vectors parse and round-trip`() {
        for (file in vectors("valid")) {
            val json = file.readText()
            val message = try {
                MessageCodec.parse(json)
            } catch (e: ProtocolException) {
                fail("${file.name} was rejected: ${e.message}")
            }
            val reserialized = MessageCodec.serialize(message)

            assertEquals(file.nameWithoutExtension.substringBefore('-'), message.type, file.name)
            assertEquals(Json.parseToJsonElement(json), Json.parseToJsonElement(reserialized), file.name)
            assertEquals(message, MessageCodec.parse(reserialized), file.name)
        }
    }

    @Test
    fun `invalid vectors are rejected as bad_message`() {
        for (file in vectors("invalid")) {
            val error = assertFailsWith<ProtocolException>(file.name) { MessageCodec.parse(file.readText()) }
            assertEquals(ErrorCode.BAD_MESSAGE, error.code, file.name)
        }
    }

    @Test
    fun `every message type has vectors`() {
        val valid = vectors("valid").map { it.nameWithoutExtension.substringBefore('-') }.toSet()
        val invalid = vectors("invalid").map { it.nameWithoutExtension.substringBefore('-') }.toSet()

        assertEquals(18, MessageTypes.ALL.size)
        for (type in MessageTypes.ALL) {
            assertTrue(type in valid, "no valid vector for $type")
            assertTrue(type in invalid, "no invalid vector for $type")
        }
    }
}
