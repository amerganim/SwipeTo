package dev.swipeto.protocol

/** An enum whose members have snake_case names on the wire. */
interface WireEnum {
    val wire: String
}

inline fun <reified T> wireValueOf(wire: String): T? where T : Enum<T>, T : WireEnum =
    enumValues<T>().firstOrNull { it.wire == wire }

enum class ErrorCode(override val wire: String) : WireEnum {
    UNSUPPORTED_MEDIA("unsupported_media"),
    NOT_PAIRED("not_paired"),
    BAD_MESSAGE("bad_message"),
    BUSY("busy"),
    VERSION_MISMATCH("version_mismatch"),
}

enum class PairFailure(override val wire: String) : WireEnum {
    WRONG_CODE("wrong_code"),
    CODE_EXPIRED("code_expired"),
    TOO_MANY_ATTEMPTS("too_many_attempts"),
    NOT_PAIRING("not_pairing"),
}

enum class MediaKind(override val wire: String) : WireEnum {
    VIDEO("video"),
    IMAGE("image"),
}

enum class Transition(override val wire: String) : WireEnum {
    NEXT("next"),
    PREVIOUS("previous"),
}

enum class ControlAction(override val wire: String) : WireEnum {
    PLAY("play"),
    PAUSE("pause"),
    SEEK("seek"),
    STOP("stop"),
}

/** A message or session rule was broken. [code] is what to send back in an `error`. */
class ProtocolException(
    val code: ErrorCode,
    message: String,
    /** The offending message's `id`, when it could be read, for `replyTo`. */
    val messageId: String? = null,
) : Exception(message) {
    fun toErrorMessage() = ErrorMessage(code, message!!.take(500), replyTo = messageId)
}
