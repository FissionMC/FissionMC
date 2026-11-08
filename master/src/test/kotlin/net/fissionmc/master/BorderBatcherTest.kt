package net.fissionmc.master

import net.fissionmc.protocol.*
import kotlin.test.*

class BorderBatcherTest {
    private val ow = "minecraft:overworld"
    /** a owns chunk x < 0, b owns x >= 0 */
    private fun batcher(you: String) = BorderBatcher({ you }) { _, cx, _ -> if (cx < 0) "a" else "b" }

    @Test fun `own and unowned positions are not forwarded`() {
        val a = batcher("a")
        assertFalse(a.addTick(ow, BorderTick(-5, 64, 0, true, "minecraft:water", 5, 0)), "x=-5 is a's own chunk")
        assertFalse(a.addNeighbor(ow, BorderNeighbor(-1, 64, 0, "minecraft:stone")))
        assertTrue(a.drain().isEmpty())
        assertFalse(BorderBatcher({ "a" }) { _, _, _ -> null }.addTick(ow, BorderTick(0, 0, 0, false, "minecraft:sand", 2, 0)), "no owner known: run it locally")
    }

    @Test fun `ghost edge work goes to the owner of the chunk, batched per owner and dimension`() {
        val a = batcher("a")
        assertTrue(a.addTick(ow, BorderTick(0, 64, 3, true, "minecraft:water", 5, 0)))
        assertTrue(a.addTick(ow, BorderTick(15, 64, 3, false, "minecraft:frosted_ice", 80, 0)))
        assertTrue(a.addTick("minecraft:the_nether", BorderTick(0, 10, 0, true, "minecraft:lava", 30, 0)))
        assertTrue(a.addNeighbor(ow, BorderNeighbor(0, 64, 3, "minecraft:stone")))
        val out = a.drain()
        assertEquals(2, out.size); assertTrue(out.all { it.origin == "a" && it.toBackend == "b" })
        val o = out.first { it.dimension == ow }; assertEquals(2, o.ticks.size); assertEquals(1, o.neighbors.size)
        assertTrue(a.drain().isEmpty(), "drained")
    }

    @Test fun `identical ticks in one flush are merged`() {
        val a = batcher("b").also { assertFalse(it.addTick(ow, BorderTick(3, 1, 1, true, "minecraft:water", 5, 0))) }
        val t = BorderTick(-3, 1, 1, true, "minecraft:water", 5, 0)
        assertTrue(a.addTick(ow, t)); assertTrue(a.addTick(ow, t.copy()))
        assertEquals(1, a.drain().single().ticks.size)
    }

    @Test fun `a flush is split at the message limit`() {
        val a = batcher("a")
        repeat(MAX_BORDER_ITEMS + 10) { a.addNeighbor(ow, BorderNeighbor(it, 0, 0, "minecraft:stone")) }
        val out = a.drain(); assertEquals(2, out.size); assertEquals(MAX_BORDER_ITEMS, out[0].neighbors.size); assertEquals(10, out[1].neighbors.size)
    }
}
