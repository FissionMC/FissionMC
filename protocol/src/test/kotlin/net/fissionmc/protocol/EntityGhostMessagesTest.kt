package net.fissionmc.protocol

import io.netty.buffer.Unpooled
import kotlin.test.*

class EntityGhostMessagesTest {
    @Test fun `a mass removal is split so that every batch decodes`() {
        val reg = MessageRegistry.core().registerEntityGhostMessages()
        val removed = List(3 * MAX_ENTITY_GHOSTS) { it.toLong() }   // 1.5 x the uuids the decoder takes, as longs
        val parts = splitRemoved(removed)
        assertTrue(parts.size > 1); assertEquals(removed, parts.flatten())
        for (p in parts) { val b = Unpooled.buffer(); reg.encode(EntityGhostBatch("a", "minecraft:overworld", emptyList(), p), b); assertEquals(p, (reg.decode(b) as EntityGhostBatch).removed) }
        val whole = Unpooled.buffer(); reg.encode(EntityGhostBatch("a", "d", emptyList(), removed), whole)
        assertFailsWith<IllegalArgumentException> { reg.decode(whole) }
    }

    @Test fun `the item give messages use their own wire ids and round trip`() {
        val reg = MessageRegistry.core().registerEntityGhostMessages()
        val b = Unpooled.buffer(); reg.encode(ItemGiveTracked("a", "b", 1, 2, 3, 4, byteArrayOf(9, 8)), b)
        val g = reg.decode(b) as ItemGiveTracked
        assertEquals(listOf("a", "b"), listOf(g.toBackend, g.origin)); assertEquals(listOf(1L, 2L, 3L, 4L), listOf(g.entityHigh, g.entityLow, g.playerHigh, g.playerLow)); assertContentEquals(byteArrayOf(9, 8), g.stack)
        val c = Unpooled.buffer(); reg.encode(ItemGiveAck("b", 5, 6, true), c)
        val k = reg.decode(c) as ItemGiveAck; assertEquals("b", k.toBackend); assertEquals(5L, k.entityHigh); assertTrue(k.accepted)
        assertEquals(211, reg.idOf(ItemGiveAck("x", 0, 0, false))); assertEquals(212, reg.idOf(g)); assertEquals(110, reg.idOf(ItemGive("a", 0, 0, ByteArray(0))))
    }
}
