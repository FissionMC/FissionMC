package net.fissionmc.master

import net.fissionmc.master.region.RegionMap
import net.fissionmc.master.region.RegionSwitchPolicy
import net.fissionmc.protocol.RegionRule
import kotlin.test.*

class RegionSwitchPolicyTest {
    private val dim = "minecraft:overworld"
    private val map = RegionMap(mapOf(dim to listOf(RegionRule(dim, Int.MIN_VALUE, Int.MIN_VALUE, 0, Int.MAX_VALUE, "a"), RegionRule(dim, 0, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, "b"))))
    private val sec = 1_000_000_000L

    @Test fun `the player must be margin blocks inside the new region`() {
        val p = RegionSwitchPolicy(8)
        assertNull(p.decide(map, dim, 5, 0, "a", 0)); assertNull(p.decide(map, dim, 7, 0, "a", 0)); assertEquals("b", p.decide(map, dim, 9, 0, "a", 0))
    }

    @Test fun `no second switch during the cooldown`() {
        val p = RegionSwitchPolicy(8); p.switched("a", 10 * sec)
        assertNull(p.decide(map, dim, -50, 0, "b", 11 * sec)); assertEquals("a", p.decide(map, dim, -50, 0, "b", 14 * sec))
    }

    @Test fun `going straight back needs twice the margin`() {
        val p = RegionSwitchPolicy(8); p.switched("a", 10 * sec)
        assertNull(p.decide(map, dim, -9, 0, "b", 15 * sec), "9 blocks inside a is enough normally, not right after leaving a")
        assertEquals("a", p.decide(map, dim, -17, 0, "b", 15 * sec))
        assertEquals("a", p.decide(map, dim, -9, 0, "b", 50 * sec), "after the window the normal margin applies again")
    }

    @Test fun `walking along the border never flaps`() {
        val p = RegionSwitchPolicy(8); var cur = "a"; var switches = 0; var t = 0L
        // zig-zag across the border line within +-6 blocks for a minute: never 8 blocks inside the other region
        for (i in 0 until 600) { t += sec / 10; val x = if (i % 2 == 0) 6 else -6; val d = p.decide(map, dim, x, i, cur, t); if (d != null) { switches++; p.switched(cur, t); cur = d } }
        assertEquals(0, switches)
    }

    @Test fun `no decision when the dimension has no owner`() { assertNull(RegionSwitchPolicy(8).decide(RegionMap.OFF, dim, 100, 0, "a", 0)) }
}
