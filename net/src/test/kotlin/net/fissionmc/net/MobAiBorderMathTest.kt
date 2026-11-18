package net.fissionmc.net

import kotlin.test.*

class MobAiBorderMathTest {
    @Test fun `restricted only when the destination is on the other side than the mob`() {
        assertFalse(MobAiBorderMath.restricted(destMine = true, mobMine = true))
        assertTrue(MobAiBorderMath.restricted(destMine = false, mobMine = true))
        assertTrue(MobAiBorderMath.restricted(destMine = true, mobMine = false))
        assertFalse(MobAiBorderMath.restricted(destMine = false, mobMine = false))
    }

    @Test fun `landing follows the minecraft yaw`() {
        assertEquals(10.0, MobAiBorderMath.landingX(10.0, 0f, 2.0), 1e-9); assertEquals(12.0, MobAiBorderMath.landingZ(10.0, 0f, 2.0), 1e-9)
        assertEquals(8.0, MobAiBorderMath.landingX(10.0, 90f, 2.0), 1e-9)   // yaw 90 = -x
        assertEquals(12.0, MobAiBorderMath.landingX(10.0, -90f, 2.0), 1e-9)
    }

    @Test fun `hop distance grows with size and is capped`() {
        assertEquals(2.0, MobAiBorderMath.hopDistance(1), 1e-9)
        assertTrue(MobAiBorderMath.hopDistance(4) > MobAiBorderMath.hopDistance(1))
        assertEquals(6.0, MobAiBorderMath.hopDistance(100), 1e-9)
    }

    // border at x = 0: x >= 0 is allowed ("mine"), x < 0 is not
    private val mine = { x: Int, _: Int -> x >= 0 }

    @Test fun `a hop that stays on the own side keeps its yaw`() {
        assertEquals(0f, MobAiBorderMath.safeYaw(5.0, 5.0, 0f, 2.0, mine))
    }

    @Test fun `a hop across the border is turned around`() {
        val y = MobAiBorderMath.safeYaw(1.0, 5.0, 90f, 2.0, mine)   // heading -x from x=1 lands at -1
        assertNotNull(y); assertEquals(-90f, y)
    }

    @Test fun `no safe direction gives null`() {
        assertNull(MobAiBorderMath.safeYaw(1.0, 5.0, 90f, 2.0) { _, _ -> false })
    }

    @Test fun `wrap keeps yaw in range`() {
        assertEquals(-170f, MobAiBorderMath.wrap(190f)); assertEquals(0f, MobAiBorderMath.wrap(360f)); assertEquals(90f, MobAiBorderMath.wrap(-270f))
    }

    @Test fun `first refused node`() {
        assertEquals(-1, MobAiBorderMath.firstRefused(5) { true })
        assertEquals(3, MobAiBorderMath.firstRefused(5) { it < 3 })
        assertEquals(-1, MobAiBorderMath.firstRefused(0) { false })
    }

    @Test fun `clamp step stops the component that crosses`() {
        // border at x = 0 (x >= 0 allowed)
        assertContentEquals(doubleArrayOf(0.1, 0.2), MobAiBorderMath.clampStep(1.0, 5.0, 0.1, 0.2, mine))
        assertContentEquals(doubleArrayOf(0.0, 0.2), MobAiBorderMath.clampStep(0.05, 5.0, -0.1, 0.2, mine))   // slides along the border
        assertContentEquals(doubleArrayOf(0.0, 0.0), MobAiBorderMath.clampStep(0.05, 5.0, -0.1, 0.0, mine))
        assertContentEquals(doubleArrayOf(0.0, 0.0), MobAiBorderMath.clampStep(0.05, 5.0, -0.1, 0.2) { _, _ -> false })
    }
}
