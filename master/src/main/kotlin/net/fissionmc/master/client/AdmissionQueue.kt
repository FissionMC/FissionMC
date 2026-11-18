package net.fissionmc.master.client

import net.fissionmc.net.ConfigException
import net.fissionmc.net.ConfigSection

/** `limbo:` of fission.yml. */
data class LimboSettings(val admitPerSecond: Double = 2.0, val burst: Int = 4, val noticeSeconds: Int = 2, val loadGraceSeconds: Int = 5) {
    companion object {
        fun from(root: ConfigSection): LimboSettings {
            val d = LimboSettings(); val s = root.section("limbo")
            val rate = s.string("admit-per-second", d.admitPerSecond.toString()).toDoubleOrNull()?.also { if (it < 0.1 || it > 1000.0) throw ConfigException("limbo.admit-per-second: expected 0.1-1000, got $it") } ?: throw ConfigException("limbo.admit-per-second: expected a number")
            return LimboSettings(rate, s.int("burst", d.burst).also { if (it !in 1..1000) throw ConfigException("limbo.burst: expected 1-1000, got $it") },
                s.int("notice-seconds", d.noticeSeconds).also { if (it !in 1..60) throw ConfigException("limbo.notice-seconds: expected 1-60, got $it") },
                s.int("load-grace-seconds", d.loadGraceSeconds).also { if (it !in 1..60) throw ConfigException("limbo.load-grace-seconds: expected 1-60, got $it") })
        }
    }
}

/** First come, first served admission of limbo players to the backends at a limited rate; nobody is admitted while no backend is up. */
class AdmissionQueue<T : Any>(private val perSecond: Double, private val burst: Int, private val nanos: () -> Long = System::nanoTime) {
    private val waiting = LinkedHashSet<T>()
    private var tokens = burst.toDouble()
    private var last = nanos()

    @Synchronized fun enqueue(c: T) { waiting.add(c) }
    @Synchronized fun remove(c: T) { waiting.remove(c) }

    /** @return the 1-based place of [c], or 0 when it is not waiting */
    @Synchronized fun position(c: T): Int { var i = 1; for (w in waiting) { if (w === c) return i; i++ }; return 0 }
    @Synchronized fun size(): Int = waiting.size
    @Synchronized fun snapshot(): List<T> = waiting.toList()

    /** @return the players admitted now, in order; refills the tokens first */
    @Synchronized fun admit(backendsUp: Boolean): List<T> {
        val now = nanos()
        tokens = Math.min(burst.toDouble(), tokens + (now - last) / 1e9 * perSecond); last = now
        if (!backendsUp) return emptyList()
        val out = ArrayList<T>()
        val it = waiting.iterator()
        while (it.hasNext() && tokens >= 1.0) { out += it.next(); it.remove(); tokens -= 1.0 }
        return out
    }
}
