package net.fissionmc.master.safety

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the safety layer did, by reason (`/fission status`). Reasons look like `custom_payload.too_big`. */
object SafetyStats {
    private val dropped = ConcurrentHashMap<String, AtomicLong>()
    private val sanitised = ConcurrentHashMap<String, AtomicLong>()
    private val disconnected = ConcurrentHashMap<String, AtomicLong>()
    private val bytesSaved = AtomicLong()
    private val checked = AtomicLong()
    private val unparsed = AtomicLong()
    fun unparsed() { unparsed.incrementAndGet() }
    fun unparsedCount() = unparsed.get()

    fun checked() { checked.incrementAndGet() }
    fun drop(reason: String) { dropped.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet() }
    fun sanitise(reason: String, savedBytes: Int = 0) { sanitised.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet(); if (savedBytes > 0) bytesSaved.addAndGet(savedBytes.toLong()) }
    fun disconnect(reason: String) { disconnected.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet() }

    fun totals() = Triple(dropped.values.sumOf { it.get() }, sanitised.values.sumOf { it.get() }, disconnected.values.sumOf { it.get() })
    fun reset() { dropped.clear(); sanitised.clear(); disconnected.clear(); bytesSaved.set(0); checked.set(0); unparsed.set(0) }

    private fun fmt(m: Map<String, AtomicLong>) = m.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value.get()}" }.ifEmpty { "-" }
    /** One or more lines for `/fission status`. */
    fun status(cfg: SafetyConfig): List<String> {
        if (!cfg.enabled) return listOf("client safety: OFF (safety.enabled: false)")
        val (d, s, x) = totals()
        return listOf("client safety: on, ${checked.get()} large frames checked, $d dropped, $s sanitised (${bytesSaved.get()} bytes removed), $x players disconnected, ${unparsed.get()} frames not understood (forwarded)",
            "  dropped: ${fmt(dropped)}", "  sanitised: ${fmt(sanitised)}", "  disconnected: ${fmt(disconnected)}")
    }
}
