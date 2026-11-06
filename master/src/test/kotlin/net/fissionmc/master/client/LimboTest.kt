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

    @Test fun `packetevents ids used by the master for state switches`() {
        println("PE-IDS play-clientbound start_configuration=${PacketType.Play.Server.CONFIGURATION_START.getId(v)} keep_alive=${PacketType.Play.Server.KEEP_ALIVE.getId(v)} " +
            "config-clientbound finish=${PacketType.Configuration.Server.CONFIGURATION_END.getId(v)} keep_alive=${PacketType.Configuration.Server.KEEP_ALIVE.getId(v)}")
    }

    @Test fun `play packets are produced for the recorded overworld id`() {
        val frames = Limbo.playFrames(100, Limbo.config.overworldDimensionId)
        assertEquals(5, frames.size)
        assertEquals(PacketType.Play.Server.JOIN_GAME.getId(v), Limbo.idOf(frames[0]))
    }

    // ---- the recorded chunk, parsed with the layout of vanilla's own writer (LevelChunkSection.write / PalettedContainer.write) ----

    private fun varInt(b: ByteBuf) = VarInts.tryRead(b)!!

    /** Skips one palette container, returns (bitsPerEntry, palette values) ; vanilla: `byte bits; palette; fixed-size long[]` (size from bits, no prefix). */
    private fun container(b: ByteBuf, entries: Int, minBitsForGlobal: Int, linearMax: Int): Pair<Int, List<Int>> {
        val bits = b.readUnsignedByte().toInt()
        val palette: List<Int> = when {
            bits == 0 -> listOf(varInt(b))                                          // SingleValuePalette: the value only
            bits >= minBitsForGlobal -> emptyList()                                 // global palette: ids stored directly
            else -> List(varInt(b)) { varInt(b) }                                   // Linear/Hash palette: size + ids
        }
        if (bits in 1 until minBitsForGlobal) assertTrue(palette.isNotEmpty(), "a palette container with bits=$bits needs palette entries (client: MissingPaletteEntryException)")
        val longs = if (bits == 0) 0 else (entries + (64 / bits) - 1) / (64 / bits)   // SimpleBitStorage: values never straddle longs
        b.skipBytes(longs * 8)
        return bits to palette
    }

    /** Number of biomes the recorded configuration announces (worldgen/biome registry frame). */
    private fun biomeRegistrySize(): Int {
        val reg = PacketType.Configuration.Server.REGISTRY_DATA.getId(v)
        for (f in Limbo.config.afterKnownPacks) {
            if (Limbo.idOf(f) != reg) continue
            val b = Unpooled.wrappedBuffer(f); VarInts.tryRead(b)
            if (str(b) == "minecraft:worldgen/biome") return varInt(b)
        }
        error("no biome registry in the recording")
    }

    @Test fun `the recorded limbo chunk has 24 sections with valid single-value biome and block containers`() {
        val b = Unpooled.wrappedBuffer(Limbo.chunkFrame)
        assertEquals(PacketType.Play.Server.CHUNK_DATA.getId(v), varInt(b))
        assertEquals(0, b.readInt()); assertEquals(0, b.readInt())                 // chunk x, z
        repeat(varInt(b)) { varInt(b); repeat(varInt(b)) { b.readLong() } }         // heightmaps: type, long[] (varint length)
        val dataLen = varInt(b); val data = b.readSlice(dataLen)
        val biomes = biomeRegistrySize()
        var sections = 0
        while (data.isReadable) {
            data.readShort(); data.readShort()                                       // non-empty block count, fluid count
            val (_, bPal) = container(data, 4096, 9, 8)
            assertTrue(bPal.all { it >= 0 }, "block state ids")
            val (mBits, mPal) = container(data, 64, 4, 3)
            assertTrue(mPal.all { it in 0 until biomes } || mBits >= 4, "biome ids $mPal must be < registry size $biomes")
            sections++
        }
        // overworld: data/minecraft/dimension_type/overworld.json has min_y -64 and height 384 -> 24 sections of 16
        assertEquals(24, sections, "section count of the overworld")
    }
}
