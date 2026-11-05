package net.fissionmc.master.client

import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.Side
import kotlin.test.*

class LimboTest {
    private val v = ClientVersion.V_26_3
    init { FissionPacketEventsAPI.install(Side.MASTER) }

    private fun str(b: ByteBuf): String { val n = VarInts.tryRead(b)!!; val a = ByteArray(n); b.readBytes(a); return String(a, Charsets.UTF_8) }

    /** registry -> tag name -> entry ids, parsed from the recorded vanilla update-tags frame. */
    private fun tags(): Map<String, Map<String, List<Int>>> {
        val frame = Limbo.config.afterKnownPacks.single { Limbo.idOf(it) == PacketType.Configuration.Server.UPDATE_TAGS.getId(v) }
        val b = Unpooled.wrappedBuffer(frame); VarInts.tryRead(b)
        return (0 until VarInts.tryRead(b)!!).associate {
            val reg = str(b)
            reg to (0 until VarInts.tryRead(b)!!).associate { str(b) to List(VarInts.tryRead(b)!!) { VarInts.tryRead(b)!! } }
        }.also { assertEquals(0, b.readableBytes(), "tags frame fully parsed") }
    }

    @Test fun `recorded configuration has the expected shape`() {
        val c = Limbo.config
        val ids = (c.beforeKnownPacks + c.afterKnownPacks).map { Limbo.idOf(it) }
        assertEquals(PacketType.Configuration.Server.SELECT_KNOWN_PACKS.getId(v), ids[c.beforeKnownPacks.size - 1])
        assertEquals(PacketType.Configuration.Server.CONFIGURATION_END.getId(v), ids.last())
        assertTrue(PacketType.Configuration.Server.UPDATE_ENABLED_FEATURES.getId(v) in ids)
        assertTrue(ids.count { it == PacketType.Configuration.Server.REGISTRY_DATA.getId(v) } > 20)
        assertEquals(0, c.overworldDimensionId)
    }

    @Test fun `every tag the real client reported missing is in the tags packet`() {
        val have = tags()
        val missing = Limbo::class.java.getResourceAsStream("/client-missing-tags.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        assertTrue(missing.size >= 40, "fixture should hold the client's 43 tags")
        val absent = missing.filter { l -> val (reg, tag) = l.split(' '); have[reg]?.get(tag) == null }
        assertEquals(emptyList(), absent)
        // and the tags are not empty shells for the ones that matter
        assertTrue(have.getValue("minecraft:block").getValue("minecraft:infiniburn_overworld").isNotEmpty())
    }

    @Test fun `play packets are produced for the recorded overworld id`() {
        val frames = Limbo.playPackets(100, Limbo.config.overworldDimensionId).map { Limbo.encode(it) }
        assertEquals(6, frames.size)
        assertEquals(PacketType.Play.Server.JOIN_GAME.getId(v), Limbo.idOf(frames[0]))
    }
}
