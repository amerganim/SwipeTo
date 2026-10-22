package dev.swipeto.sender.aim

import kotlinx.coroutines.withTimeoutOrNull

/** A paired screen that is online and could be the target. */
data class AimCandidate(val id: String, val name: String, val lastUsedAt: Long? = null)

enum class ResolvedBy {
    /** The rear camera read the screen's QR marker. */
    CAMERA,

    /** The phone faces the screen's learned compass bearing. */
    COMPASS,

    /** The only candidate. */
    ONLY_SCREEN,

    /** Several faced screens and no marker: the one used most recently. */
    MOST_RECENT,
}

/** The chosen screen, and the marker nonce if the camera read one (to confirm with `aim_ack`). */
data class ResolvedTarget(val id: String, val nonce: String?, val by: ResolvedBy)

/**
 * Picks which screen the phone is pointing at. More resolvers (BLE signal + compass, UWB) slot in
 * behind this interface later; [ChainResolver] tries them in order of preference.
 */
fun interface TargetResolver {
    suspend fun resolve(candidates: List<AimCandidate>): ResolvedTarget?
}

/** Reads QR markers with the camera. Returns the first marker [accept] likes, or null on timeout. */
fun interface MarkerScanner {
    suspend fun firstMarker(timeoutMs: Long, accept: (MarkerPayload) -> Boolean): MarkerPayload?
}

/** Preferred: whichever candidate's marker the rear camera sees. */
class CameraMarkerResolver(
    private val scanner: MarkerScanner,
    private val timeoutMs: () -> Long,
    private val enabled: () -> Boolean = { true },
) : TargetResolver {
    override suspend fun resolve(candidates: List<AimCandidate>): ResolvedTarget? {
        if (!enabled() || candidates.isEmpty()) return null
        val ids = candidates.map { it.id }.toSet()
        val marker = withTimeoutOrNull(timeoutMs() + 500) { scanner.firstMarker(timeoutMs()) { it.receiverId in ids } }
        return marker?.let { ResolvedTarget(it.receiverId, it.nonce, ResolvedBy.CAMERA) }
    }
}

/** Without a marker: the only paired screen online, else the one used most recently. */
object FallbackResolver : TargetResolver {
    override suspend fun resolve(candidates: List<AimCandidate>): ResolvedTarget? = when (candidates.size) {
        0 -> null
        1 -> ResolvedTarget(candidates[0].id, null, ResolvedBy.ONLY_SCREEN)
        else -> {
            val recent = candidates.filter { it.lastUsedAt != null }.maxByOrNull { it.lastUsedAt!! }
            (recent ?: candidates.minBy { it.name.lowercase() }).let { ResolvedTarget(it.id, null, ResolvedBy.MOST_RECENT) }
        }
    }
}

class ChainResolver(private val resolvers: List<TargetResolver>) : TargetResolver {
    override suspend fun resolve(candidates: List<AimCandidate>): ResolvedTarget? =
        resolvers.firstNotNullOfOrNull { it.resolve(candidates) }
}
