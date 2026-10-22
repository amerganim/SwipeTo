package dev.swipeto.sender.aim

import dev.swipeto.sender.net.SenderConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What aiming looks like right now, for the UI and for swipe-up. */
sealed interface AimState {
    data object Idle : AimState

    /** Raised: every candidate screen glows; looking for which one is in front. */
    data class Searching(val screens: List<String>) : AimState

    /** A target is chosen. [verified] = the screen confirmed the camera read its live marker. */
    data class Targeted(val target: LinkTarget, val by: ResolvedBy, val verified: Boolean) : AimState

    /** Raised, but no paired screen is reachable. */
    data object NoScreens : AimState
}

/**
 * Raise detected → `aim_start` to every paired screen online (all glow and show markers) → choose
 * the one in front → confirm its marker nonce (`aim_start {nonce}` → `aim_ack`) → `aim_end` to the
 * others. Swipe-up then takes the target's open connection for the handoff ([takeTarget]).
 *
 * Tuned on a Galaxy A15, where people swipe the moment the screen glows:
 * - With a single paired screen it is the target **at once**; the camera ([preferred]) keeps
 *   looking and upgrades the lock to verified if it reads the marker.
 * - A swipe while still searching among several screens takes the [fallback]'s best guess.
 * - The swipe itself tips the phone down, so lowering keeps the target for [graceMs] before the
 *   aim really ends.
 */
class AimController(
    private val scope: CoroutineScope,
    private val links: StandbyLinks,
    private val candidates: () -> List<Pair<LinkTarget, Long?>>,
    private val preferred: TargetResolver,
    private val fallback: TargetResolver = FallbackResolver,
    private val graceMs: Long = 1_200,
    private val onLocked: (AimState.Targeted) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<AimState>(AimState.Idle)
    val state: StateFlow<AimState> = _state.asStateFlow()

    private var job: Job? = null
    private var glow: Job? = null
    private var ending: Job? = null
    private var aimed: Map<String, SenderConnection> = emptyMap()
    private var aimedCandidates: List<Pair<LinkTarget, Long?>> = emptyList()

    fun onAimStart() {
        if (ending?.isActive == true) {
            // Raised again within the grace period: carry on with the same target.
            ending?.cancel()
            return
        }
        if (_state.value !is AimState.Idle && _state.value !is AimState.NoScreens) return
        job?.cancel()
        job = scope.launch {
            val screens = candidates()
            val opened = screens.map { (target, _) -> async { target to links.get(target) } }.awaitAll()
                .mapNotNull { (target, conn) -> conn?.let { target to it } }
            if (opened.isEmpty()) {
                _state.value = AimState.NoScreens
                return@launch
            }
            aimed = opened.associate { (target, conn) -> target.id to conn }
            aimedCandidates = screens.filter { it.first.id in aimed }
            opened.forEach { (_, conn) -> runCatching { conn.aimStart() } }
            log("aiming at ${opened.size} screen(s)")
            startGlowHeartbeat()

            if (opened.size == 1) {
                target(opened[0].first, ResolvedBy.ONLY_SCREEN, verified = false)
            } else {
                _state.value = AimState.Searching(opened.map { it.first.name })
            }

            // Look for a marker; with several screens fall back if none is seen.
            val read = preferred.resolve(aimedCandidates.toAimCandidates())
            val chosen = read ?: if (opened.size > 1) fallback.resolve(aimedCandidates.toAimCandidates()) else null
            val target = chosen?.let { c -> opened.firstOrNull { it.first.id == c.id }?.first }
            if (chosen == null || target == null) {
                if (opened.size > 1) log("no target resolved")
                return@launch
            }
            val verified = chosen.nonce?.let { nonce ->
                runCatching { aimed.getValue(target.id).lock(nonce).locked }.getOrDefault(false)
            } ?: false
            val current = _state.value as? AimState.Targeted
            if (current == null || current.target.id != target.id || verified != current.verified) {
                target(target, chosen.by, verified)
            }
        }
    }

    /** Lowered without a swipe: after [graceMs] every screen stops glowing (a swipe in between still sends). */
    fun onAimEnd() {
        if (_state.value is AimState.Idle || ending?.isActive == true) return
        ending = scope.launch {
            delay(graceMs)
            endNow()
        }
    }

    /** Ends the aim at once (the app went to the background, raise-to-aim was turned off...). */
    fun endNow() {
        ending?.cancel()
        job?.cancel()
        glow?.cancel()
        aimed.values.forEach { runCatching { it.aimEnd() } }
        aimed = emptyMap()
        _state.value = AimState.Idle
    }

    /**
     * Swipe-up: the chosen screen and its open connection, now owned by the caller (no `aim_end`;
     * the handoff itself hides the glow). While still searching among several screens, the
     * fallback's best guess. Null if no screen is reachable.
     */
    suspend fun takeTarget(): Pair<LinkTarget, SenderConnection>? {
        val target = when (val state = _state.value) {
            is AimState.Targeted -> state.target
            is AimState.Searching -> {
                val guess = fallback.resolve(aimedCandidates.toAimCandidates()) ?: return null
                aimedCandidates.firstOrNull { it.first.id == guess.id }?.first ?: return null
            }
            else -> return null
        }
        ending?.cancel()
        job?.cancel()
        glow?.cancel()
        aimed.filterKeys { it != target.id }.values.forEach { runCatching { it.aimEnd() } }
        aimed = emptyMap()
        _state.value = AimState.Idle
        val conn = links.take(target.id) ?: return null
        return target to conn
    }

    private fun target(target: LinkTarget, by: ResolvedBy, verified: Boolean) {
        aimed.filterKeys { it != target.id }.values.forEach { runCatching { it.aimEnd() } }
        aimed = aimed.filterKeys { it == target.id }
        val firstLock = _state.value !is AimState.Targeted
        val targeted = AimState.Targeted(target, by, verified)
        _state.value = targeted
        log("target ${target.name} by $by, verified=$verified")
        if (firstLock || verified) onLocked(targeted)
    }

    /**
     * A screen stops glowing 10 s after the last `aim_start` (docs/protocol.md, section 5.3), so
     * repeat it while the phone stays aimed.
     */
    private fun startGlowHeartbeat() {
        glow?.cancel()
        glow = scope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                if (_state.value !is AimState.Targeted && _state.value !is AimState.Searching) return@launch
                aimed.values.forEach { runCatching { it.aimStart() } }
            }
        }
    }

    private fun List<Pair<LinkTarget, Long?>>.toAimCandidates() = map { (t, used) -> AimCandidate(t.id, t.name, used) }

    private companion object {
        const val HEARTBEAT_MS = 4_000L
    }
}
