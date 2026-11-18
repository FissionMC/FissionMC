package net.fissionmc.net

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps one link to [address] alive: connects, completes the handshake, calls [onReady], waits for the
 * link to close, then reconnects with exponential backoff (reset only after a link that stayed up for [stableMs]: a master that closes the link right after the handshake must not be hit every [initialDelayMs]).
 * Rejections that will not fix themselves (auth/version) still retry, but at [maxDelayMs], and are reported to [onFailure].
 */
class NodeClient(
    private val transport: Transport,
    address: NodeAddress,
    private val onReady: (NodeLink, PeerInfo) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val onDisconnect: (String) -> Unit = {},
    private val initialDelayMs: Long = 500,
    private val maxDelayMs: Long = 30_000,
    /** A link that stayed up this long counts as stable: only then the reconnect back-off starts from [initialDelayMs] again. */
    private val stableMs: Long = 30_000,
) : Closeable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "node-client").apply { isDaemon = true } }
    private val stopped = AtomicBoolean()
    @Volatile var address: NodeAddress = address
        private set
    @Volatile var link: NodeLink? = null
        private set
    @Volatile private var delay = initialDelayMs
    /** The wait before the next reconnect attempt (visible for tests). */
    internal val currentDelayMs: Long get() = delay

    fun start(): NodeClient { scheduler.execute(::attempt); return this }

    /** Use another address from now on: drops the current link, the reconnect loop then dials [to]. */
    fun redirect(to: NodeAddress) {
        if (to == address) return
        address = to
        delay = initialDelayMs
        link?.close("master address changed")
    }

    private fun attempt() {
        if (stopped.get()) return
        transport.connect(address).thenCompose { l -> link = l; l.ready.thenApply { l to it } }.whenComplete { r, err ->
            if (stopped.get()) { link?.close("client stopped"); return@whenComplete }
            if (err != null) {
                val rejected = (err.cause ?: err) is LinkClosed
                onFailure(err.cause ?: err)
                link?.close("handshake failed")
                // "not authorized" / "no pending pairing": the admin is probably about to paste the pairing code, so try again soon (8 s: stays below the master's per-address failure limit)
                val pairingWait = rejected && (err.cause ?: err).message.orEmpty().let { it.startsWith(NOT_AUTHORIZED) || it.startsWith(NO_PENDING) }
                retry(if (pairingWait) minOf(maxDelayMs, 8_000L) else if (rejected) maxDelayMs else delay)
            } else {
                val (l, peer) = r
                val upAt = System.nanoTime()
                runCatching { onReady(l, peer) }.onFailure(onFailure)
                l.closed.thenAccept { reason ->
                    if (System.nanoTime() - upAt >= stableMs * 1_000_000L) delay = initialDelayMs
                    onDisconnect(reason); retry(delay)
                }
            }
        }
    }

    private fun retry(wait: Long) {
        if (stopped.get()) return
        delay = (delay * 2).coerceAtMost(maxDelayMs)
        runCatching { scheduler.schedule(::attempt, wait, TimeUnit.MILLISECONDS) }
    }

    override fun close() {
        stopped.set(true)
        link?.close("client stopped")
        scheduler.shutdownNow()
    }
}
