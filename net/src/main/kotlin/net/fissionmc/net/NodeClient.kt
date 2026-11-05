package net.fissionmc.net

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps one link to [address] alive: connects, completes the handshake, calls [onReady], waits for the
 * link to close, then reconnects with exponential backoff (reset after every successful handshake).
 * Rejections that will not fix themselves (auth/version) still retry, but at [maxDelayMs], and are reported to [onFailure].
 */
class NodeClient(
    private val transport: Transport,
    private val address: NodeAddress,
    private val onReady: (NodeLink, PeerInfo) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val onDisconnect: (String) -> Unit = {},
    private val initialDelayMs: Long = 500,
    private val maxDelayMs: Long = 30_000,
) : Closeable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "node-client").apply { isDaemon = true } }
    private val stopped = AtomicBoolean()
    @Volatile var link: NodeLink? = null
        private set
    private var delay = initialDelayMs

    fun start(): NodeClient { scheduler.execute(::attempt); return this }

    private fun attempt() {
        if (stopped.get()) return
        transport.connect(address).thenCompose { l -> link = l; l.ready.thenApply { l to it } }.whenComplete { r, err ->
            if (stopped.get()) { link?.close("client stopped"); return@whenComplete }
            if (err != null) {
                val rejected = (err.cause ?: err) is LinkClosed
                onFailure(err.cause ?: err)
                link?.close("handshake failed")
                retry(if (rejected) maxDelayMs else delay)
            } else {
                val (l, peer) = r
                delay = initialDelayMs
                runCatching { onReady(l, peer) }.onFailure(onFailure)
                l.closed.thenAccept { reason -> onDisconnect(reason); retry(delay) }
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
