package net.fissionmc.backend.node

import net.fissionmc.protocol.MasterStats

/** The latest [MasterStats] the master sent to this backend (null until the first one arrives, or in standalone mode). */
object MasterInfo {
    @Volatile var latest: MasterStats? = null; private set
    @Volatile var receivedAtNanos = 0L; private set

    fun onStats(s: MasterStats) { latest = s; receivedAtNanos = System.nanoTime() }

    /** Lines for `/fission status`. */
    fun lines(): List<String> {
        val s = latest ?: return listOf("no master statistics yet (standalone backend, or the master has not sent any)")
        val age = (System.nanoTime() - receivedAtNanos) / 1_000_000
        return s.lines() + "(snapshot ${age} ms old)"
    }

    /** Extra platform metadata for spark reports. */
    fun metadata(): Map<String, String> = latest?.metadata() ?: mapOf("fission.master" to "no statistics")
}
