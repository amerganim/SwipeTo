package dev.swipeto.sender.aim

import dev.swipeto.sender.net.SenderConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * Raise detected → `aim_start` to every paired screen online (all glow and show markers) → resolve
 * which one is in front (camera marker, else fallback) → confirm the marker nonce with that screen
 * (`aim_start {nonce}` → `aim_ack`) → `aim_end` to the others. Lowering the phone ends the aim.
 * Swipe-up then takes the target's open connection for the handoff ([takeTarget]).
 */
class AimController(
    private val scope: CoroutineScope,
    private val links: StandbyLinks,
    private val candidates: () -> List<Pair<LinkTarget, Long?>>,
    private val resolver: TargetResolver,
    private val onLocked: (AimState.Targeted) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<AimState>(AimState.Idle)
    val state: StateFlow<AimState> = _state.asStateFlow()

    private var job: Job? = null
    private var aimed: Map<String, SenderConnection> = emptyMap()

    fun onAimStart() {
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
            opened.forEach { (_, conn) -> runCatching { conn.aimStart() } }
            _state.value = AimState.Searching(opened.map { it.first.name })
            log("aiming at ${opened.size} screen(s)")

            val byId = opened.associate { it.first.id to it.first }
            val resolved = resolver.resolve(screens.filter { it.first.id in byId }.map { (t, used) -> AimCandidate(t.id, t.name, used) })
            val target = resolved?.let { byId[it.id] }
            if (resolved == null || target == null) {
                // Several screens and nothing to tell them apart: keep them all glowing, let the user aim.
                log("no target resolved")
                return@launch
            }
            val verified = resolved.nonce?.let { nonce ->
                runCatching { aimed.getValue(target.id).lock(nonce).locked }.getOrDefault(false)
            } ?: false
            aimed.filterKeys { it != target.id }.values.forEach { runCatching { it.aimEnd() } }
            aimed = aimed.filterKeys { it == target.id }
            val targeted = AimState.Targeted(target, resolved.by, verified)
            _state.value = targeted
            log("target ${target.name} by ${resolved.by}, verified=$verified")
            onLocked(targeted)
        }
    }

    /** Lowered without a swipe: every screen stops glowing. */
    fun onAimEnd() {
        job?.cancel()
        aimed.values.forEach { runCatching { it.aimEnd() } }
        aimed = emptyMap()
        _state.value = AimState.Idle
    }

    /**
     * Swipe-up: the chosen screen and its open connection, now owned by the caller (no `aim_end`;
     * the handoff itself hides the glow). Null if nothing is targeted yet.
     */
    suspend fun takeTarget(): Pair<LinkTarget, SenderConnection>? {
        val targeted = _state.value as? AimState.Targeted ?: return null
        job?.cancel()
        val conn = links.take(targeted.target.id) ?: return null
        aimed = emptyMap()
        _state.value = AimState.Idle
        return targeted.target to conn
    }
}
