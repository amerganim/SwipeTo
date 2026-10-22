package dev.swipeto.sender.aim

import dev.swipeto.protocol.Prefetch
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

/** A paired screen a raise could be aimed at: when it was last used and how the phone faces it. */
data class AimOption(val target: LinkTarget, val lastUsedAt: Long?, val facing: Facing)

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
 * Pointing (compass + camera, see [PointingFilter]):
 * - Screens the phone faces **away** from (learned bearing, other direction) are left alone.
 * - A screen it faces (**toward**) can be the target at once; with several, the camera
 *   ([preferred]) decides, else the [fallback]'s most recently used.
 * - A screen whose direction is **unknown** (never locked yet) glows and shows its marker, but only
 *   the camera can make it the target; that lock teaches its direction.
 *
 * Once a target is chosen its `aim_start` carries [prefetch] (what a swipe would send, at the
 * current position), repeated with every heartbeat, so the screen has the video loaded and paused
 * by the time the swipe arrives (docs/protocol.md, 5.3). Screens that merely glow get no prefetch.
 *
 * Tuned on the A15 test phone, where people swipe the moment the screen glows: a single faced screen is
 * targeted immediately (the camera may still upgrade it to verified), a swipe while still choosing
 * takes the best guess, and lowering keeps the target for [graceMs] because the swipe itself tips
 * the phone down.
 */
class AimController(
    private val scope: CoroutineScope,
    private val links: StandbyLinks,
    private val candidates: () -> List<AimOption>,
    private val preferred: TargetResolver,
    private val fallback: TargetResolver = FallbackResolver,
    private val graceMs: Long = 1_200,
    private val onLocked: (AimState.Targeted) -> Unit = {},
    /** What a swipe would hand to [LinkTarget] right now, or null (nothing to prefetch). */
    private val prefetch: suspend (LinkTarget) -> Prefetch? = { null },
    /** The aim ended without a swipe taking the target (lowered, timed out, app left). */
    private val onEnded: () -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<AimState>(AimState.Idle)
    val state: StateFlow<AimState> = _state.asStateFlow()

    private var job: Job? = null
    private var glow: Job? = null
    private var ending: Job? = null
    private var aimed: Map<String, SenderConnection> = emptyMap()
    private var aimedOptions: List<AimOption> = emptyList()

    fun onAimStart() {
        if (ending?.isActive == true) {
            // Raised again within the grace period: carry on with the same target.
            ending?.cancel()
            return
        }
        if (_state.value !is AimState.Idle && _state.value !is AimState.NoScreens) return
        job?.cancel()
        job = scope.launch {
            val all = candidates()
            val screens = all.filter { it.facing != Facing.AWAY }
            if (screens.isEmpty()) {
                // Raised, but toward no paired screen: nothing should glow.
                log(if (all.isEmpty()) "no paired screen" else "facing away from every paired screen")
                _state.value = if (all.isEmpty()) AimState.NoScreens else AimState.Idle
                return@launch
            }
            val opened = screens.map { option -> async { option to links.get(option.target) } }.awaitAll()
                .mapNotNull { (option, conn) -> conn?.let { option to it } }
            if (opened.isEmpty()) {
                _state.value = AimState.NoScreens
                return@launch
            }
            aimed = opened.associate { (option, conn) -> option.target.id to conn }
            aimedOptions = opened.map { it.first }
            opened.forEach { (_, conn) -> runCatching { conn.aimStart() } }
            log("aiming at ${opened.size} screen(s): ${aimedOptions.joinToString { "${it.target.name} ${it.facing}" }}")
            startGlowHeartbeat()

            val toward = aimedOptions.filter { it.facing == Facing.TOWARD }
            if (toward.size == 1) {
                target(toward[0].target, ResolvedBy.COMPASS, verified = false)
            } else {
                _state.value = AimState.Searching(opened.map { it.first.target.name })
            }

            // Look for a marker among everything that glows; fall back only among screens faced.
            val read = preferred.resolve(aimedOptions.toAimCandidates())
            val chosen = read ?: if (toward.size > 1) fallback.resolve(toward.toAimCandidates()) else null
            val target = chosen?.let { c -> aimedOptions.firstOrNull { it.target.id == c.id }?.target }
            if (chosen == null || target == null) {
                if (toward.size != 1) log("no target resolved")
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
        val wasAiming = aimed.isNotEmpty()
        aimed = emptyMap()
        _state.value = AimState.Idle
        if (wasAiming) onEnded()
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
                // Only a screen the phone faces may be guessed; an unknown one needs the camera.
                val toward = aimedOptions.filter { it.facing == Facing.TOWARD }
                val guess = fallback.resolve(toward.toAimCandidates()) ?: return null
                toward.firstOrNull { it.target.id == guess.id }?.target ?: return null
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
        val previous = (_state.value as? AimState.Targeted)?.target?.id
        val firstLock = previous == null
        val targeted = AimState.Targeted(target, by, verified)
        _state.value = targeted
        log("target ${target.name} by $by, verified=$verified")
        if (firstLock || verified) onLocked(targeted)
        if (previous != target.id) sendPrefetch(target)
    }

    private fun sendPrefetch(target: LinkTarget) = scope.launch {
        val conn = aimed[target.id] ?: return@launch
        val what = runCatching { prefetch(target) }.getOrNull() ?: return@launch
        if ((_state.value as? AimState.Targeted)?.target?.id != target.id) return@launch
        runCatching { conn.aimStart(what) }
        log("prefetch ${what.media.title} at ${what.positionMs} ms on ${target.name}")
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
                val state = _state.value
                if (state !is AimState.Targeted && state !is AimState.Searching) return@launch
                if (state is AimState.Targeted) {
                    // Keep the screen's prefetch at the phone's position (it re-seeks if far off).
                    val what = runCatching { prefetch(state.target) }.getOrNull()
                    aimed[state.target.id]?.let { conn -> runCatching { conn.aimStart(what) } }
                } else {
                    aimed.values.forEach { runCatching { it.aimStart() } }
                }
            }
        }
    }

    private fun List<AimOption>.toAimCandidates() = map { AimCandidate(it.target.id, it.target.name, it.lastUsedAt) }

    private companion object {
        const val HEARTBEAT_MS = 4_000L
    }
}
