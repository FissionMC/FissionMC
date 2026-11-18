package net.fissionmc.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Per sender (a plugin name, or "node/plugin" on the master) limits for plugin messages: [perSecond] messages in any one second window and [maxInflight] bytes
 * accepted but not yet written to the link. [acquire] returns null when the message may go (and counts it), else the reason; [release] when its write ended.
 */
class PluginQuota(private val maxInflight: Long, private val perSecond: Int, private val clock: () -> Long = System::nanoTime) {
    private class Bucket { var windowStart = 0L; var count = 0; var inflight = 0L }
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun acquire(sender: String, bytes: Int): String? {
        val b = buckets.computeIfAbsent(sender) { Bucket() }
        synchronized(b) {
            val now = clock()
            if (now - b.windowStart >= 1_000_000_000L) { b.windowStart = now; b.count = 0 }
            if (b.count >= perSecond) return "over $perSecond plugin messages per second (limits.plugin-rate)"
            if (b.inflight + bytes > maxInflight) return "over ${maxInflight shr 10} KiB of plugin messages in flight (limits.plugin-inflight): the link is slower than the plugin sends"
            b.count++; b.inflight += bytes
        }
        return null
    }

    fun release(sender: String, bytes: Int) { buckets[sender]?.let { b -> synchronized(b) { b.inflight = maxOf(0, b.inflight - bytes) } } }

    fun inflight(sender: String): Long = buckets[sender]?.let { synchronized(it) { it.inflight } } ?: 0
    /** Forgets idle senders (a plugin that was unloaded). */
    fun forget(sender: String) { buckets.remove(sender) }
}
