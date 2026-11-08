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

    @Test fun `ghost demand sync and fix survive the wire`() {
        val reg = MessageRegistry.core().registerRegionMessages()
        val ids = IntArray(SectionCodec.SIZE) { if (it < 100) 0 else if (it < 300) 7 else 12 }
        val sec = GhostSection(3, SectionCodec.encode(ids))
        for (m in listOf<NodeMessage>(GhostDemand("a", "b", ow, longArrayOf(1L, -5L)), GhostSync("a", "b", ow, -3, 9, intArrayOf(1, -2, 3)), GhostFix("b", "a", ow, -3, 9, listOf(sec)))) {
            val b = io.netty.buffer.Unpooled.buffer(); reg.encode(m, b); val d = reg.decode(b); assertEquals(0, b.readableBytes())
            when (m) {
                is GhostDemand -> { d as GhostDemand; assertEquals(m.toBackend, d.toBackend); assertContentEquals(m.chunks, d.chunks) }
                is GhostSync -> { d as GhostSync; assertEquals(m.cx, d.cx); assertEquals(m.cz, d.cz); assertContentEquals(m.hashes, d.hashes) }
                is GhostFix -> { d as GhostFix; assertEquals(1, d.sections.size); assertContentEquals(ids, SectionCodec.decode(d.sections[0].runs)); assertEquals(3, d.sections[0].index) }
                else -> error("")
            }
        }
    }

    @Test fun `section run length coding round trips and rejects bad runs`() {
        val ids = IntArray(SectionCodec.SIZE) { (it / 7) % 5 }
        assertContentEquals(ids, SectionCodec.decode(SectionCodec.encode(ids)))
        assertEquals(2, SectionCodec.encode(IntArray(SectionCodec.SIZE)).size, "an empty section is one run")
        assertFailsWith<IllegalArgumentException> { SectionCodec.decode(intArrayOf(1, 10)) }
        assertFailsWith<IllegalArgumentException> { SectionCodec.decode(intArrayOf(1, SectionCodec.SIZE + 1)) }
        val a = ids.copyOf(); a[4000] = 99
        assertNotEquals(SectionCodec.hash(ids), SectionCodec.hash(a)); assertEquals(SectionCodec.hash(ids), SectionCodec.hash(ids.copyOf()))
    }

    @Test fun `ghost sync scheduler checks new and reloaded chunks first, the rest at a low rate`() {
        val s = GhostSyncScheduler(100, 2)
        fun c(k: Long, i: Int = 1) = GhostSyncScheduler.Candidate(k, i)
        assertEquals(listOf(1L, 2L), s.pick(0, listOf(c(1), c(2), c(3))).map { it.key }, "budget 2")
        assertEquals(listOf(3L), s.pick(1, listOf(c(1), c(2), c(3))).map { it.key })
        assertTrue(s.pick(50, listOf(c(1), c(2), c(3))).isEmpty(), "nothing due before the interval")
        assertEquals(listOf(1L, 2L), s.pick(101, listOf(c(1), c(2), c(3))).map { it.key }, "oldest due first")
        assertEquals(listOf(2L), s.pick(102, listOf(c(1), c(2, 9))).map { it.key }, "a reloaded chunk (other instance) is checked at once")
        s.recheck(1); assertEquals(listOf(1L), s.pick(103, listOf(c(1))).map { it.key })
    }
}
