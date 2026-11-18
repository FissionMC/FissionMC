package net.fissionmc.net

import kotlin.test.*

class PacerTest {
    private var now = 0L
    private fun ms(x: Long) { now += x * 1_000_000L }

    @Test fun `at 20 ticks per second a one second pacer fires about once per 20 ticks`() {
        val p = Pacer(1000, clock = { now }); var fired = 0
        repeat(200) { if (p.due()) fired++; ms(50) }
        assertEquals(10, fired)
    }
    @Test fun `at one tick per second it fires on every tick`() {
        val p = Pacer(1000, clock = { now }); var fired = 0
        repeat(10) { if (p.due()) fired++; ms(1000) }
        assertEquals(10, fired)
    }
    @Test fun `after a long tick it fires once, not once per missed period`() {
        val p = Pacer(1000, clock = { now }); assertTrue(p.due()); ms(30_000); assertTrue(p.due()); ms(50); assertFalse(p.due())
    }
    @Test fun `a tick a few ms early still counts`() {
        val p = Pacer(100, clock = { now }); assertTrue(p.due()); ms(95); assertTrue(p.due()); ms(50); assertFalse(p.due())
    }
    @Test fun `effective tick time is the configured one or the time since the last tick`() {
        assertEquals(1000, TickPace.effectiveMs(1000, 40_000_000)); assertEquals(8000, TickPace.effectiveMs(50, 8_000_000_000)); assertEquals(60_000, TickPace.effectiveMs(50, 900_000_000_000))
        assertEquals(25, TickPace.budgetMs(50)); assertEquals(500, TickPace.budgetMs(1000)); assertEquals(500, TickPace.budgetMs(60_000))
    }
}
