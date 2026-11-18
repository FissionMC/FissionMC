package net.fissionmc.protocol

import io.netty.buffer.Unpooled
import kotlin.test.*

class AckListsTest {
    private val reg = MessageRegistry.core().registerPlayerMessages().registerRegionMessages()

    @Test fun `an ack with more uuids than the decoder takes is split and every part decodes`() {
        val uuids = List(MAX_ENTITY_BLOBS * 2 + 600) { it.toLong() }   // blobs plus their passengers
        val parts = splitUuidPairs(uuids)
        assertTrue(parts.size > 1); assertEquals(uuids, parts.flatten()); assertTrue(parts.all { it.size % 2 == 0 })
        for (p in parts) {
            val a = Unpooled.buffer(); reg.encode(EntityHandoverAck("a", p), a); assertEquals(p, (reg.decode(a) as EntityHandoverAck).spawned)
            val t = Unpooled.buffer(); reg.encode(EntityTransferAck(1, 2, p), t); assertEquals(p, (reg.decode(t) as EntityTransferAck).spawned)
        }
        assertEquals(listOf(emptyList<Long>()), splitUuidPairs(emptyList()))
    }
}

class PermissionNodesTest {
    @Test fun `a permission message with hundreds of nodes decodes whole, oversized nodes are dropped`() {
        val reg = MessageRegistry.core().registerPlayerMessages()
        val nodes = List(300) { "plugin.node.$it" } + "x".repeat(200)
        val b = Unpooled.buffer(); reg.encode(PlayerPermission(1, 2, 3, 4, nodes), b)
        val d = reg.decode(b) as PlayerPermission
        assertEquals(nodes.take(300), d.nodes); assertEquals(0, b.readableBytes())
    }
}
