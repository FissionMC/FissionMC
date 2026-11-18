package net.fissionmc.protocol

import io.netty.buffer.Unpooled
import kotlin.test.*

class LimitsTest {
    private val reg = MessageRegistry.core().registerLimitsMessages().registerChunkMessages().registerEntityGhostMessages().registerRegionMessages()

    @Test fun `limits survive the wire and unknown fields are skipped`() {
        val l = Limits(frame = 3 shl 20, itemBlob = 123_456, entityGhosts = 77)
        val b = Unpooled.buffer(); l.write(b)
        assertEquals(l, Limits.read(b))
        val newer = Unpooled.buffer().writeByte(2).writeByte(40).writeInt(5).writeByte(0).writeInt(999)   // a field this version does not know (40), then frame
        newer.readerIndex(0)
        val r = Limits.read(Unpooled.buffer().writeByte(2).writeByte(40).writeInt(5).writeByte(0).writeInt(999))
        assertEquals(999, r.frame)                           // known field read, unknown skipped, missing ones are the legacy values
        assertEquals(Limits.LEGACY.itemBlob, r.itemBlob)
    }

    @Test fun `the minimum of both sides applies per field`() {
        val m = Limits(frame = 8 shl 20, itemBlob = 100_000).min(Limits(frame = 16 shl 20, itemBlob = 50_000, entityGhosts = 10))
        assertEquals(8 shl 20, m.frame); assertEquals(50_000, m.itemBlob); assertEquals(10, m.entityGhosts)
    }

    @Test fun `validation names the key`() {
        assertFailsWith<IllegalArgumentException> { Limits.validate(Limits(frame = 0)) }.also { assertTrue("limits.frame" in it.message!!) }
        assertFailsWith<IllegalArgumentException> { Limits.validate(Limits(frame = (1 shl 30) + 1, maxMessage = 1 shl 30)) }
        assertFailsWith<IllegalArgumentException> { Limits.validate(Limits(frame = 32 shl 20, maxMessage = 16 shl 20)) }.also { assertTrue("max-message" in it.message!!) }
        assertFailsWith<IllegalArgumentException> { Limits.validate(Limits(frame = 8 shl 20, maxMessage = 8 shl 20)) }.also { assertTrue("chunk-blob" in it.message!!) }   // 16M blob cannot fit in an 8M message
        Limits.validate(Limits())
        Limits.validate(Limits.LEGACY)
    }

    @Test fun `codec caps follow the effective limits and name the limit`() {
        val chunk = ChunkData(1, 0, 1, ByteArray(6 shl 20))
        val b = Unpooled.buffer(); reg.encode(chunk, b)                               // default 16M: fine
        assertEquals(6 shl 20, (reg.decode(b) as ChunkData).bytes.size)
        val e = assertFailsWith<IllegalArgumentException> { Limits.towards(Limits.LEGACY) { reg.encode(chunk, Unpooled.buffer()) } }   // a peer that accepts 4 MiB
        assertTrue("exceeds" in e.message!!, e.message)
        // decoding uses our own caps: an array longer than it, from a peer with a higher limit, is refused with a readable reason
        val wire = Unpooled.buffer(); reg.encode(chunk, wire)
        val prev = Limits.own()
        try { Limits.setOwn(Limits(frame = 2 shl 20, maxMessage = 4 shl 20, chunkBlob = 2 shl 20, playerData = 2 shl 20, entityBlob = 2 shl 20, itemBlob = 1 shl 20, entityGhostBlob = 1 shl 20, playerFrame = 2 shl 20, maxPluginMessage = 2 shl 20))
            assertTrue("limit" in assertFailsWith<IllegalArgumentException> { reg.decode(wire) }.message!!)
        } finally { Limits.setOwn(prev) }
        assertEquals(prev, Limits.own())
    }

    @Test fun `plugin message codec`() {
        val m = NetPluginMessage("o", "t", "plug", "a:b", ByteArray(1000) { it.toByte() })
        val b = Unpooled.buffer(); reg.let { MessageRegistry.core().registerLimitsMessages() }.encode(m, b)
        val d = MessageRegistry.core().registerLimitsMessages().decode(b) as NetPluginMessage
        assertEquals("o|a:b", d.orderKey); assertContentEquals(m.data, d.data)
    }

    @Test fun `the former constants are the defaults now and the legacy ones are what an old peer implies`() {
        assertEquals(16 shl 20, MAX_CHUNK_BLOB); assertEquals(4 shl 20, MAX_ITEM_BLOB); assertEquals(256, MAX_ITEM_TRANSFER)
        assertEquals(65536, Limits.LEGACY.entityGhostBlob); assertEquals(64, Limits.LEGACY.entityBlobs)
    }

    @Test fun `ghost leash links survive the wire`() {
        val m = EntityGhostLinks("backend-b", "minecraft:overworld", listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L))
        val b = Unpooled.buffer(); reg.encode(m, b)
        val r = reg.decode(b) as EntityGhostLinks
        assertEquals("backend-b", r.origin); assertEquals(m.links, r.links)
    }
}
