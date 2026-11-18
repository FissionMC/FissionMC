package net.fissionmc.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers what arrived here by a border handover: such an entity is not handed back within [ttlMs] (a borderline entity, or a leashed pair whose members stand on
 * both sides of the border, would otherwise bounce between the two backends every second). Thread safe.
 */
class ArrivalCooldown<K : Any>(private val ttlMs: Long) {
    private val at = ConcurrentHashMap<K, Long>()

    fun arrived(key: K, now: Long = System.currentTimeMillis()) {
        at[key] = now
        if (at.size > 4096) at.entries.removeIf { now - it.value > ttlMs }
    }

    fun recent(key: K, now: Long = System.currentTimeMillis()): Boolean {
        val t = at[key] ?: return false
        if (now - t > ttlMs) { at.remove(key, t); return false }
        return true
    }
}
