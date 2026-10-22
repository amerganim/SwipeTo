package dev.swipeto.sender.aim

import dev.swipeto.protocol.SessionState
import dev.swipeto.sender.data.TrustTokens
import dev.swipeto.sender.net.SenderConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A paired screen as standby links see it. */
data class LinkTarget(val id: String, val name: String, val webSocketUrl: String)

/**
 * Open, greeted (`hello` accepted) connections to paired screens, kept ready while the player is in
 * front of the user, so `aim_start` reaches a screen at once instead of after a connect + hello.
 * Unpaired screens are never contacted here: pairing needs the user, not a raised arm.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StandbyLinks(
    private val scope: CoroutineScope,
    private val trust: TrustTokens,
    private val connect: suspend (url: String) -> SenderConnection,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val links = mutableMapOf<String, Deferred<SenderConnection?>>()

    /** Opens links to [targets] that are paired and not linked yet; drops links to screens no longer listed. */
    suspend fun warm(targets: List<LinkTarget>) = mutex.withLock {
        val wanted = targets.filter { trust.get(it.id) != null }.associateBy { it.id }
        (links.keys - wanted.keys).toList().forEach { id -> links.remove(id)?.let { closeLater(it) } }
        for ((id, target) in wanted) {
            val existing = links[id]
            if (existing != null && !existing.isFailedOrClosed()) continue
            links[id] = scope.async { open(target) }
        }
    }

    /** A ready connection to [target], opening one if needed; null if the screen refuses or is unreachable. */
    suspend fun get(target: LinkTarget): SenderConnection? {
        val pending = mutex.withLock {
            val existing = links[target.id]
            if (existing != null && !existing.isFailedOrClosed()) existing
            else scope.async { open(target) }.also { links[target.id] = it }
        }
        return pending.await()?.takeIf { it.isReady() }
    }

    /** Hands the connection over (to the handoff coordinator); standby forgets it. */
    suspend fun take(id: String): SenderConnection? = mutex.withLock { links.remove(id) }?.await()?.takeIf { it.isReady() }

    suspend fun closeAll() = mutex.withLock {
        links.values.forEach { closeLater(it) }
        links.clear()
    }

    private suspend fun open(target: LinkTarget): SenderConnection? {
        val token = trust.get(target.id) ?: return null
        return try {
            val conn = connect(target.webSocketUrl)
            val ack = conn.hello(token)
            if (ack.accepted) {
                log("standby link to ${target.name} ready")
                conn
            } else {
                log("standby link to ${target.name} refused: ${ack.reason}")
                if (ack.reason == dev.swipeto.protocol.ErrorCode.NOT_PAIRED) trust.remove(target.id)
                conn.close()
                null
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log("standby link to ${target.name} failed: ${e.message}")
            null
        }
    }

    private fun SenderConnection.isReady() = !closed.value && state == SessionState.SESSION

    private fun Deferred<SenderConnection?>.isFailedOrClosed(): Boolean {
        if (!isCompleted) return false
        if (isCancelled) return true
        val conn = runCatching { getCompleted() }.getOrNull() ?: return true
        return !conn.isReady()
    }

    private fun closeLater(link: Deferred<SenderConnection?>) {
        if (link.isCompleted) runCatching { link.getCompleted()?.close() } else link.cancel()
    }
}
