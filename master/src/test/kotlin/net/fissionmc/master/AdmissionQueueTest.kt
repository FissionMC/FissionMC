package net.fissionmc.master

import net.fissionmc.master.client.AdmissionQueue
import net.fissionmc.master.client.LimboSettings
import kotlin.test.*

class AdmissionQueueTest {
    private var t = 0L
    private fun queue(rate: Double = 2.0, burst: Int = 2) = AdmissionQueue<String>(rate, burst) { t }

    @Test fun `a burst is admitted at once, the rest at the configured rate and in order`() {
        val q = queue(); listOf("a", "b", "c", "d").forEach { q.enqueue(it) }
        assertEquals(listOf("a", "b"), q.admit(true))
        assertEquals(emptyList(), q.admit(true))
        t += 500_000_000L
        assertEquals(listOf("c"), q.admit(true))
        t += 1_000_000_000L
        assertEquals(listOf("d"), q.admit(true))
        assertEquals(0, q.size())
    }

    @Test fun `nobody is admitted while no backend is up and tokens do not pile up beyond the burst`() {
        val q = queue(rate = 10.0, burst = 2); listOf("a", "b", "c").forEach { q.enqueue(it) }
        t += 60_000_000_000L
        assertEquals(emptyList(), q.admit(false))
        assertEquals(listOf("a", "b"), q.admit(true))
        assertEquals(1, q.size())
    }

    @Test fun `positions count from one, enqueue is idempotent and remove drops a player`() {
        val q = queue(); q.enqueue("a"); q.enqueue("b"); q.enqueue("a")
        assertEquals(2, q.size()); assertEquals(1, q.position("a")); assertEquals(2, q.position("b")); assertEquals(0, q.position("z"))
        q.remove("a"); assertEquals(1, q.position("b"))
    }

    @Test fun `limbo settings have defaults and reject bad values`() {
        val d = LimboSettings.from(net.fissionmc.net.FissionConfig.parse(""))
        assertEquals(2.0, d.admitPerSecond); assertEquals(4, d.burst)
        assertFailsWith<net.fissionmc.net.ConfigException> { LimboSettings.from(net.fissionmc.net.FissionConfig.parse("limbo:\n  admit-per-second: 0")) }
        assertEquals(5.0, LimboSettings.from(net.fissionmc.net.FissionConfig.parse("limbo:\n  admit-per-second: 5")).admitPerSecond)
    }
}
