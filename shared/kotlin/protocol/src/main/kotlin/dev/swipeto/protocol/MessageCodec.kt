package dev.swipeto.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Strict JSON codec for v1 messages. [parse] enforces the same rules as docs/schemas: required
 * fields, types, patterns, enums, conditional fields, and no unknown fields.
 */
object MessageCodec {
    const val MAX_MESSAGE_BYTES = 64 * 1024

    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val TRUST_TOKEN = Regex("^[A-Za-z0-9_-]{22,128}$")
    private val PAIR_CODE = Regex("^[0-9]{6}$")
    private val MARKER_NONCE = Regex("^[A-Za-z0-9_-]{16,64}$")
    private val URL = Regex("^https?://[^\\s]+$")
    private val MIME = Regex("^(video|image)/[A-Za-z0-9.+-]+$")

    fun parse(json: String): Message {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) throw bad("Message is larger than 64 KiB")
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            throw bad("Not valid JSON")
        }
        if (root !is JsonObject) throw bad("A message must be a JSON object")

        // Read the id first so a later failure can still be correlated with replyTo.
        val idForReply = (root["id"] as? JsonPrimitive)?.takeIf { it.isString && UUID.matches(it.content) }?.content
        return try {
            parseObject(Fields(root, "message"))
        } catch (e: ProtocolException) {
            if (e.messageId == null && idForReply != null) throw ProtocolException(e.code, e.message!!, idForReply) else throw e
        }
    }

    private fun parseObject(f: Fields): Message {
        val type = f.string("type")
        val id = f.matching("id", UUID)
        val ts = f.integer("ts")
        val replyTo = f.optionalMatching("replyTo", UUID)

        val message = when (type) {
            MessageTypes.PAIR_REQUEST -> PairRequest(f.matching("senderId", UUID), f.text("senderName", 1, 64), id, ts, replyTo)
            MessageTypes.PAIR_CONFIRM -> PairConfirm(f.matching("code", PAIR_CODE), id, ts, replyTo)
            MessageTypes.PAIR_RESULT -> parsePairResult(f, id, ts, replyTo)
            MessageTypes.HELLO -> Hello(
                f.matching("senderId", UUID), f.text("senderName", 1, 64), f.matching("trustToken", TRUST_TOKEN),
                parseVersion(f), id, ts, replyTo,
            )
            MessageTypes.HELLO_ACK -> HelloAck(
                f.matching("receiverId", UUID), f.bool("accepted"), f.optionalEnum<ErrorCode>("reason"), id, ts, replyTo,
            )
            MessageTypes.AIM_START -> AimStart(f.optionalMatching("nonce", MARKER_NONCE), parsePrefetch(f.optionalObj("prefetch")), id, ts, replyTo)
            MessageTypes.AIM_ACK -> AimAck(f.bool("locked"), id, ts, replyTo)
            MessageTypes.AIM_END -> AimEnd(id, ts, replyTo)
            MessageTypes.HANDOFF -> Handoff(
                parseMedia(f.obj("media")), f.integer("positionMs"), f.bool("playing"), f.optionalEnum<Transition>("transition"), id, ts, replyTo,
            )
            MessageTypes.HANDOFF_ACK -> HandoffAck(f.bool("accepted"), f.optionalEnum<ErrorCode>("reason"), id, ts, replyTo)
            MessageTypes.CONTROL -> Control(f.enum<ControlAction>("action"), f.optionalInteger("positionMs"), id, ts, replyTo)
            MessageTypes.STATE -> PlaybackState(f.integer("positionMs"), f.bool("playing"), f.bool("ended"), id, ts, replyTo)
            MessageTypes.PULLBACK_REQUEST -> PullbackRequest(id, ts, replyTo)
            MessageTypes.PULLBACK -> Pullback(f.integer("positionMs"), f.bool("playing"), id, ts, replyTo)
            MessageTypes.PING -> Ping(id, ts, replyTo)
            MessageTypes.PONG -> Pong(id, ts, replyTo)
            MessageTypes.ERROR -> ErrorMessage(f.enum<ErrorCode>("code"), f.text("message", 0, 500), id, ts, replyTo)
            else -> throw bad("Unknown message type '$type'")
        }

        when {
            message is HelloAck && !message.accepted && message.reason == null ->
                throw bad("hello_ack: reason is required when not accepted")
            message is HandoffAck && !message.accepted && message.reason == null ->
                throw bad("handoff_ack: reason is required when not accepted")
            message is Control && message.action == ControlAction.SEEK && message.positionMs == null ->
                throw bad("control: positionMs is required for seek")
        }

        f.rejectUnknown()
        return message
    }

    private fun parseVersion(f: Fields): Int {
        val version = f.integer("protocolVersion")
        if (version < 1 || version > Int.MAX_VALUE) throw bad("protocolVersion must be an integer >= 1")
        return version.toInt()
    }

    private fun parsePairResult(f: Fields, id: String, ts: Long, replyTo: String?): PairResult {
        val ok = f.bool("ok")
        val token = f.optionalMatching("trustToken", TRUST_TOKEN)
        val reason = f.optionalEnum<PairFailure>("reason")
        return when {
            ok && token != null && reason == null -> PairResult(true, token, null, id, ts, replyTo)
            !ok && token == null && reason != null -> PairResult(false, null, reason, id, ts, replyTo)
            ok -> throw bad("pair_result: ok=true needs trustToken and no reason")
            else -> throw bad("pair_result: ok=false needs reason and no trustToken")
        }
    }

    private fun parsePrefetch(p: Fields?): Prefetch? {
        if (p == null) return null
        val prefetch = Prefetch(parseMedia(p.obj("media")), p.integer("positionMs"))
        p.rejectUnknown()
        return prefetch
    }

    private fun parseMedia(m: Fields): MediaInfo {
        val media = MediaInfo(
            m.enum<MediaKind>("kind"),
            m.matching("url", URL, maxLength = 2048),
            m.matching("mime", MIME),
            m.text("title", 0, 200),
            m.optionalInteger("durationMs"),
        )
        m.rejectUnknown()
        return media
    }

    fun serialize(message: Message): String = buildJsonObject {
        put("type", message.type)
        put("id", message.id)
        put("ts", message.ts)
        message.replyTo?.let { put("replyTo", it) }
        writePayload(message)
    }.toString()

    private fun JsonObjectBuilder.writePayload(message: Message) {
        when (message) {
            is PairRequest -> {
                put("senderId", message.senderId)
                put("senderName", message.senderName)
            }
            is PairConfirm -> put("code", message.code)
            is PairResult -> {
                put("ok", message.ok)
                message.trustToken?.let { put("trustToken", it) }
                message.reason?.let { put("reason", it.wire) }
            }
            is Hello -> {
                put("senderId", message.senderId)
                put("senderName", message.senderName)
                put("trustToken", message.trustToken)
                put("protocolVersion", message.protocolVersion)
            }
            is HelloAck -> {
                put("receiverId", message.receiverId)
                put("accepted", message.accepted)
                message.reason?.let { put("reason", it.wire) }
            }
            is Handoff -> {
                putMedia(message.media)
                put("positionMs", message.positionMs)
                put("playing", message.playing)
                message.transition?.let { put("transition", it.wire) }
            }
            is HandoffAck -> {
                put("accepted", message.accepted)
                message.reason?.let { put("reason", it.wire) }
            }
            is Control -> {
                put("action", message.action.wire)
                message.positionMs?.let { put("positionMs", it) }
            }
            is PlaybackState -> {
                put("positionMs", message.positionMs)
                put("playing", message.playing)
                put("ended", message.ended)
            }
            is Pullback -> {
                put("positionMs", message.positionMs)
                put("playing", message.playing)
            }
            is ErrorMessage -> {
                put("code", message.code.wire)
                put("message", message.description)
            }
            is AimStart -> {
                message.nonce?.let { put("nonce", it) }
                message.prefetch?.let { prefetch ->
                    putJsonObject("prefetch") {
                        putMedia(prefetch.media)
                        put("positionMs", prefetch.positionMs)
                    }
                }
            }
            is AimAck -> put("locked", message.locked)
            is AimEnd, is PullbackRequest, is Ping, is Pong -> Unit
        }
    }

    private fun JsonObjectBuilder.putMedia(media: MediaInfo) = putJsonObject("media") {
        put("kind", media.kind.wire)
        put("url", media.url)
        put("mime", media.mime)
        put("title", media.title)
        media.durationMs?.let { put("durationMs", it) }
    }

    internal fun bad(reason: String) = ProtocolException(ErrorCode.BAD_MESSAGE, reason)

    /** Reads fields from one JSON object and remembers which were read, to reject the rest. */
    private class Fields(private val obj: JsonObject, private val where: String) {
        private val read = mutableSetOf<String>()

        private fun get(name: String): JsonElement? {
            read += name
            return obj[name]
        }

        private fun required(name: String) = get(name) ?: throw bad("$where: missing field '$name'")

        private fun primitive(name: String) = required(name) as? JsonPrimitive

        fun string(name: String): String {
            val v = primitive(name)
            return if (v != null && v.isString) v.content else throw bad("$where: '$name' must be a string")
        }

        // JSON Schema counts string length in Unicode code points, not UTF-16 units.
        fun text(name: String, minLength: Int, maxLength: Int): String {
            val s = string(name)
            val length = s.codePointCount(0, s.length)
            return if (length in minLength..maxLength) s else throw bad("$where: '$name' must be $minLength–$maxLength characters")
        }

        fun matching(name: String, pattern: Regex, maxLength: Int = Int.MAX_VALUE): String {
            val s = string(name)
            return if (s.length <= maxLength && pattern.matches(s)) s else throw bad("$where: '$name' has an invalid format")
        }

        fun optionalMatching(name: String, pattern: Regex): String? = if (get(name) == null) null else matching(name, pattern)

        fun integer(name: String): Long {
            val v = primitive(name)
            val n = if (v != null && !v.isString) v.content.toLongOrNull() else null
            return when {
                n == null -> throw bad("$where: '$name' must be an integer")
                n < 0 -> throw bad("$where: '$name' must not be negative")
                else -> n
            }
        }

        fun optionalInteger(name: String): Long? = if (get(name) == null) null else integer(name)

        fun bool(name: String): Boolean {
            val v = primitive(name)
            return when {
                v == null || v.isString -> throw bad("$where: '$name' must be a boolean")
                v.content == "true" -> true
                v.content == "false" -> false
                else -> throw bad("$where: '$name' must be a boolean")
            }
        }

        inline fun <reified T> enum(name: String): T where T : Enum<T>, T : WireEnum =
            wireValueOf<T>(string(name)) ?: throw bad("$where: '$name' has an unknown value")

        inline fun <reified T> optionalEnum(name: String): T? where T : Enum<T>, T : WireEnum =
            if (get(name) == null) null else enum<T>(name)

        fun optionalObj(name: String): Fields? = if (get(name) == null) null else obj(name)

        fun obj(name: String): Fields {
            val v = required(name)
            return if (v is JsonObject) Fields(v, name) else throw bad("$where: '$name' must be an object")
        }

        fun rejectUnknown() {
            obj.keys.firstOrNull { it !in read }?.let { throw bad("$where: unexpected field '$it'") }
        }
    }
}
