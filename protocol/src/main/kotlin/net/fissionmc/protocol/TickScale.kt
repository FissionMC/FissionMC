package net.fissionmc.protocol

/**
 * Backend to master (213): how long the backend's game thread currently takes per tick, in ms (50 = normal, 1000 = `/tick rate 1`, more while a long tick or a freeze is in progress).
 * Everything the master asks of a backend's game thread (a flush, a login, a chat event, a permission check, a chunk lease) is given [TickScale.scale] times as long.
 */
class NodeTickRate(val msPerTick: Int) : NodeMessage

/** Turns a timeout that was tuned for 20 ticks per second into one that fits a backend that ticks slower. Pure. */
object TickScale {
    const val NORMAL_MS = 50
    /** Never more than this many times the base value (1 tick per second = 20 x; a longer pause is not waited for). */
    const val MAX_FACTOR = 20.0

    fun factor(msPerTick: Int): Double = (msPerTick.coerceAtLeast(1).toDouble() / NORMAL_MS).coerceIn(1.0, MAX_FACTOR)
    fun scale(baseMs: Long, msPerTick: Int): Long = (baseMs * factor(msPerTick)).toLong()
}
