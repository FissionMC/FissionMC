package net.fissionmc.net

/**
 * "Every [periodMs] of wall-clock time" for work that runs on the game thread but must not depend on the tick rate: call [due] once per tick; it is true at most once per period.
 * At 20 ticks per second it fires about as often as `ticks % n == 0` did; at 1 tick per second it fires on every tick (a duty that was "every 20 ticks" is then every second, not every 20 s),
 * and after a long tick it fires once, not once per missed period. [slackMs] (at most 10 ms) lets a tick that comes a few ms early still count (20 tps jitter).
 * Game thread only (not thread safe).
 */
class Pacer(private val periodMs: Long, private val slackMs: Long = minOf(periodMs / 10, 10L), private val clock: () -> Long = System::nanoTime) {
    private var last = Long.MIN_VALUE
    fun due(): Boolean {
        val now = clock()
        if (last != Long.MIN_VALUE && now - last < (periodMs - slackMs) * 1_000_000L) return false
        last = now; return true
    }
    /** The next [due] is true. */
    fun reset() { last = Long.MIN_VALUE }
}

/** The ms per tick a game thread currently needs: the configured one, or the time since its last tick when that is longer (a lag spike, GC, a freeze). Pure. */
object TickPace {
    fun effectiveMs(configuredMs: Int, sinceLastTickNanos: Long, capMs: Int = 60_000): Int = maxOf(configuredMs.toLong(), sinceLastTickNanos / 1_000_000L).coerceIn(1L, capMs.toLong()).toInt()
    /** Work budget of one tick for draining queues, in ms: half the tick (a 1 tps server may use 500 ms), at least 25. */
    fun budgetMs(msPerTick: Int): Long = (msPerTick / 2L).coerceIn(25L, 500L)
}
