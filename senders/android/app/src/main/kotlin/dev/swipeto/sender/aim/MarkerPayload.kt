package dev.swipeto.sender.aim

/** What a receiver's QR marker says: `swipeto:<receiverId>:<nonce>` (docs/protocol.md, section 5.3). */
data class MarkerPayload(val receiverId: String, val nonce: String) {
    companion object {
        private val FORMAT = Regex("^swipeto:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}):([A-Za-z0-9_-]{16,64})$")

        fun parse(text: String?): MarkerPayload? {
            val match = text?.trim()?.let(FORMAT::matchEntire) ?: return null
            return MarkerPayload(match.groupValues[1].lowercase(), match.groupValues[2])
        }
    }
}
