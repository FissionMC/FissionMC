package net.fissionmc.master.seamless

import io.netty.buffer.Unpooled
import net.fissionmc.master.client.Limbo
import net.fissionmc.master.client.VarInts
import kotlin.test.*

class TransplantTest {
    private val login: ByteArray by lazy { Limbo.playFrames(100, Limbo.config.overworldDimensionId)[0] }
    private val spawn: SpawnInfo by lazy { SpawnInfo.parse(login) }

    @Test fun `a real Login frame parses into the level the client is in`() {
        assertEquals(Limbo.ENTITY_ID, spawn.playerId); assertEquals("minecraft:overworld", spawn.dimension); assertEquals(Limbo.config.overworldDimensionId, spawn.dimensionType)
        assertTrue(spawn.chunkRadius > 0 && spawn.simulationDistance > 0); assertFalse(spawn.hardcore); assertTrue("minecraft:overworld" in spawn.levels)
    }

    @Test fun `the same level is compatible and every difference is named`() {
        fun copy(dimension: String = spawn.dimension, type: Int = spawn.dimensionType, seed: Long = spawn.seed, hardcore: Boolean = spawn.hardcore, flat: Boolean = spawn.isFlat, sea: Int = spawn.seaLevel, levels: Set<String> = spawn.levels) =
            SpawnInfo(9, hardcore, levels, 500, spawn.chunkRadius + 3, spawn.simulationDistance, true, false, true, type, dimension, seed, 1, 0, spawn.isDebug, flat, 0, sea, spawn.onlineMode, spawn.enforcesSecureChat)
        assertNull(spawn.incompatibility(copy()), "player id, view distance, game mode, flags that GameEvents can change do not matter")
        assertTrue(spawn.incompatibility(copy(dimension = "minecraft:the_nether"))!!.contains("dimension"))
        assertTrue(spawn.incompatibility(copy(type = spawn.dimensionType + 1))!!.contains("dimension type"))
        assertTrue(spawn.incompatibility(copy(seed = spawn.seed + 1))!!.contains("seed"))
        assertTrue(spawn.incompatibility(copy(hardcore = true))!!.contains("hardcore"))
        assertTrue(spawn.incompatibility(copy(flat = !spawn.isFlat))!!.contains("flat"))
        assertTrue(spawn.incompatibility(copy(sea = spawn.seaLevel + 1))!!.contains("sea level"))
        assertTrue(spawn.incompatibility(copy(levels = setOf("minecraft:overworld", "x:y")))!!.contains("level list"))
    }

    @Test fun `login fields become incremental packets`() {
        val l = SpawnInfo(9, false, spawn.levels, 500, 11, 7, true, false, true, spawn.dimensionType, spawn.dimension, spawn.seed, 1, 0, false, false, 0, spawn.seaLevel, spawn.onlineMode, spawn.enforcesSecureChat)
        val f = TransplantBuilder.fromLogin(l, 42)
        assertEquals(6, f.size)
        fun read(i: Int) = Unpooled.wrappedBuffer(f[i]).also { VarInts.tryRead(it) }
        assertEquals(11, read(0).let { VarInts.tryRead(it) }); assertEquals(7, read(1).let { VarInts.tryRead(it) })
        read(2).let { assertEquals(3, it.readByte().toInt()); assertEquals(1f, it.readFloat()) }       // CHANGE_GAME_MODE survival=0? gameType 1 = creative
        read(3).let { assertEquals(11, it.readByte().toInt()); assertEquals(1f, it.readFloat()) }       // showDeathScreen false -> 1
        read(4).let { assertEquals(12, it.readByte().toInt()); assertEquals(1f, it.readFloat()) }       // limited crafting
        read(5).let { assertEquals(42, it.readInt()); assertEquals(22, it.readByte().toInt()) }          // reduced debug info -> entity event 22 on the client's own id
    }

    @Test fun `chunks outside the new view are forgotten and the ones the new backend sent or the player stands in are kept`() {
        fun pack(x: Int, z: Int) = (x.toLong() and 0xFFFFFFFFL) or ((z.toLong() and 0xFFFFFFFFL) shl 32)
        val held = listOf(pack(0, 0), pack(5, 0), pack(-9, 3), pack(30, 30), pack(40, 40))
        val out = TransplantBuilder.forgetOutside(held, sentByNew = setOf(pack(40, 40)), centerX = 0, centerZ = 0, radius = 8)
        val forgotten = out.map { Unpooled.wrappedBuffer(it).also { b -> VarInts.tryRead(b) }.readLong() }.toSet()
        assertEquals(setOf(pack(-9, 3), pack(30, 30)), forgotten)
    }

    @Test fun `the client view tracks chunks objectives teams and the player's effects`() {
        val m = EntityIdMap().also { it.start(7) }; val v = ClientView(m)
        fun f(name: String, w: io.netty.buffer.ByteBuf.() -> Unit): ByteArray { val b = Unpooled.buffer(); VarInts.write(b, PlayPacketIds.c(name)); b.w(); return ByteArray(b.readableBytes()).also { b.readBytes(it) } }
        fun str(b: io.netty.buffer.ByteBuf, s: String) { VarInts.write(b, s.length); b.writeBytes(s.toByteArray()) }
        v.observe(f("LevelChunkWithLight") { writeInt(3); writeInt(-4) }); v.observe(f("LevelChunkWithLight") { writeInt(4); writeInt(-4) })
        v.observe(f("ForgetLevelChunk") { writeLong((4L and 0xFFFFFFFFL) or ((-4L and 0xFFFFFFFFL) shl 32)) })
        assertEquals(1, v.chunks.size)
        v.observe(f("SetObjective") { str(this, "o"); writeByte(0) }); v.observe(f("SetPlayerTeam") { str(this, "t"); writeByte(0) }); v.observe(f("SetPlayerTeam") { str(this, "gone"); writeByte(0) }); v.observe(f("SetPlayerTeam") { str(this, "gone"); writeByte(1) })
        assertEquals(setOf("o"), v.objectives); assertEquals(setOf("t"), v.teams)
        v.observe(f("UpdateMobEffect") { VarInts.write(this, 7); VarInts.write(this, 1) }); v.observe(f("UpdateMobEffect") { VarInts.write(this, 99); VarInts.write(this, 2) })
        assertEquals(setOf(1), v.effects)
        v.observe(f("SetChunkCacheCenter") { VarInts.write(this, 5); VarInts.write(this, -6) }); assertEquals(5 to -6, v.centerX to v.centerZ)
        // the cleanup lists exactly what the old backend left
        m.onAdded(500, 6); m.onAdded(501, 4)
        val cl = TransplantBuilder.cleanup(m, v, listOf(byteArrayOf(1)))
        assertTrue(cl.size >= 7, "removal, tab, objective, team, effect, container, 3 weather: ${cl.size}")
        val first = Unpooled.wrappedBuffer(cl[0]).also { VarInts.tryRead(it) }; assertEquals(2, VarInts.tryRead(first)); assertEquals(500, VarInts.tryRead(first)); assertEquals(501, VarInts.tryRead(first))
    }
}
