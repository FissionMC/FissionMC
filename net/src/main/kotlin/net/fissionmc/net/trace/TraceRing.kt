package net.fissionmc.net.trace

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** The last [windowNanos] of events in memory (lock free; also bounded to [maxEntries] so a flood cannot exhaust the heap). Times are monotonic nanos passed in by the caller. */
class TraceRing(@Volatile var windowNanos: Long, private val maxEntries: Int = 400_000, private val maxChars: Long = 48L * 1024 * 1024) {
    private class Entry(val nanos: Long, val line: String)
    private val q = ConcurrentLinkedQueue<Entry>()
    private val n = AtomicInteger()
    private val chars = java.util.concurrent.atomic.AtomicLong()

    val size: Int get() = n.get()

    fun add(nanos: Long, line: String) {
        q.add(Entry(nanos, line)); n.incrementAndGet(); chars.addAndGet(line.length.toLong())
        trim(nanos)
    }

    fun trim(now: Long) {
        val w = windowNanos
        while (true) {
            val h = q.peek() ?: return
            if (now - h.nanos > w || n.get() > maxEntries || chars.get() > maxChars) { val p = q.poll(); if (p != null) { n.decrementAndGet(); chars.addAndGet(-p.line.length.toLong()) } } else return
        }
    }

    /** The lines newer than [seconds] before [now], oldest first. */
    fun snapshot(now: Long, seconds: Long = Long.MAX_VALUE / 2_000_000_000L): List<String> {
        trim(now)
        val from = if (seconds >= Long.MAX_VALUE / 2_000_000_000L) Long.MIN_VALUE else now - seconds * 1_000_000_000L
        val out = ArrayList<String>()
        for (e in q) if (e.nanos >= from) out.add(e.line)
        return out
    }

    fun clear() { q.clear(); n.set(0); chars.set(0) }
}
