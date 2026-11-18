package net.fissionmc.net

import kotlin.test.*

class ArrivalCooldownTest {
    @Test fun `an arrival is recent for the ttl only`() {
        val c = ArrivalCooldown<String>(3000)
        assertFalse(c.recent("a", 0))
        c.arrived("a", 1000)
        assertTrue(c.recent("a", 1000)); assertTrue(c.recent("a", 3999)); assertFalse(c.recent("a", 4001))
        assertFalse(c.recent("a", 4002), "forgotten once expired")
        assertFalse(c.recent("b", 1500))
    }
}
