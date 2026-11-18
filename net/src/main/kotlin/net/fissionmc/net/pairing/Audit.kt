package net.fissionmc.net.pairing

import java.util.concurrent.ConcurrentHashMap

/**
 * Audit lines for the security relevant events of the node link (pair, remove, rotate, failed authentication). One line per event, `[audit] <event> ...`, to [sink] (stdout by default,
 * which is the console and what a panel records). Never contains key material or pairing secrets: only node ids, addresses and fingerprints. Text that comes from a peer is cleaned of
 * control characters and cut, so a peer cannot forge log lines. Failures are throttled per source (a flood of bad logins is one line plus a count), see [failure].
 */
object Audit {
    @Volatile var sink: (String) -> Unit = { println(it) }
    @Volatile var clock: () -> Long = System::currentTimeMillis

    fun clean(s: String, max: Int = 80): String = buildString { for (c in s.take(max)) append(if (c.isISOControl() || c == ' ' || c == ' ') '?' else c) } + if (s.length > max) "..." else ""

    fun event(text: String) = sink("[audit] " + clean(text, 600))

    private class Throttle(var windowStart: Long, var suppressed: Int)
    private val throttles = ConcurrentHashMap<String, Throttle>()

    /** A failed attempt from [source]: the first one per [windowMs] is logged (with the number of suppressed ones since the last line), the rest only counted. */
    fun failure(source: String, text: String, windowMs: Long = 10_000) {
        val now = clock()
        if (throttles.size > 4096) throttles.entries.removeIf { now - it.value.windowStart > windowMs * 6 }
        val t = throttles.computeIfAbsent(source) { Throttle(0, 0) }
        val line: String? = synchronized(t) {
            if (now - t.windowStart >= windowMs) { val n = t.suppressed; t.windowStart = now; t.suppressed = 0; if (n > 0) "$text (+$n similar from $source suppressed)" else text }
            else { t.suppressed++; null }
        }
        if (line != null) event("failed $line")
    }
}
