package net.fissionmc.master.seamless

import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.fissionmc.master.client.VarInts
import kotlin.test.*

class EntityRewriterTest {
    private val V = ClientVersion.V_26_3
    private fun frame(name: String, f: ByteBuf.() -> Unit): ByteArray { val b = Unpooled.buffer(); VarInts.write(b, PlayPacketIds.c(name)); b.f(); return ByteArray(b.readableBytes()).also { b.readBytes(it) } }
    private fun sframe(name: String, f: ByteBuf.() -> Unit): ByteArray { val b = Unpooled.buffer(); VarInts.write(b, PlayPacketIds.s(name)); b.f(); return ByteArray(b.readableBytes()).also { b.readBytes(it) } }
    private fun ByteBuf.v(x: Int) = VarInts.write(this, x)
    private fun ByteBuf.str(s: String) { val t = s.toByteArray(); v(t.size); writeBytes(t) }
    private fun r(frame: ByteArray) = Unpooled.wrappedBuffer(frame).also { VarInts.tryRead(it) }
    private fun ByteBuf.rv() = VarInts.tryRead(this)!!

    @Test fun `packet ids of the table agree with PacketEvents for the packets the master already decodes`() {
        mapOf("Login" to PacketType.Play.Server.JOIN_GAME, "Respawn" to PacketType.Play.Server.RESPAWN, "GameEvent" to PacketType.Play.Server.CHANGE_GAME_STATE, "AddEntity" to PacketType.Play.Server.SPAWN_ENTITY,
            "RemoveEntities" to PacketType.Play.Server.DESTROY_ENTITIES, "SetEntityData" to PacketType.Play.Server.ENTITY_METADATA, "LevelChunkWithLight" to PacketType.Play.Server.CHUNK_DATA,
            "ForgetLevelChunk" to PacketType.Play.Server.UNLOAD_CHUNK, "PlayerPosition" to PacketType.Play.Server.PLAYER_POSITION_AND_LOOK, "ChunkBatchFinished" to PacketType.Play.Server.CHUNK_BATCH_END,
            "SetPassengers" to PacketType.Play.Server.SET_PASSENGERS, "SetEntityMotion" to PacketType.Play.Server.ENTITY_VELOCITY, "SetEquipment" to PacketType.Play.Server.ENTITY_EQUIPMENT,
            "SetChunkCacheCenter" to PacketType.Play.Server.UPDATE_VIEW_POSITION, "BossEvent" to PacketType.Play.Server.BOSS_BAR, "SystemChat" to PacketType.Play.Server.SYSTEM_CHAT_MESSAGE).forEach { (n, t) ->
            assertEquals(t.getId(V), PlayPacketIds.c(n), n)
        }
        assertEquals(PacketType.Play.Client.PLAYER_LOADED.getId(V), PlayPacketIds.s("PlayerLoaded"))
        assertEquals(PacketType.Play.Client.INTERACT_ENTITY.getId(V), PlayPacketIds.s("Interact"))
    }

    @Test fun `every packet class of the table has a rule`() {
        val rw = EntityRewriter(EntityIdMap().also { it.start(1) })
        val table = EntityIdTableTestAccess.entityPackets()
        val c = table.filter { it.startsWith("Clientbound") }.map { it.removePrefix("Clientbound").removeSuffix("Packet") }
        val ids = c.flatMap { n -> PlayPacketIds.clientbound.filterKeys { it == n || (n == "MoveEntity" && it.startsWith("MoveEntity")) || (n == "MoveMinecart" && it.startsWith("MoveMinecart")) }.values }
        val missing = ids.filter { it !in rw.handledClientbound && it != PlayPacketIds.c("Login") && it != PlayPacketIds.c("DebugEntityValue") }
        assertTrue(missing.isEmpty(), "no rule for ${missing.map { id -> PlayPacketIds.clientbound.entries.first { it.value == id }.key }}")
        val s = table.filter { it.startsWith("Serverbound") }.map { it.removePrefix("Serverbound").removeSuffix("Packet") }
        s.forEach { n -> assertTrue(PlayPacketIds.s(n) in rw.handledServerbound, "no serverbound rule for $n") }
    }

    private fun mapped(): Pair<EntityIdMap, EntityRewriter> { val m = EntityIdMap(); m.start(7); m.transplantTo(55); return m to EntityRewriter(m) }

    @Test fun `identity mode leaves every frame byte for byte alone`() {
        val m = EntityIdMap().also { it.start(7) }; val rw = EntityRewriter(m)
        val frames = listOf(
            frame("Animate") { v(12); writeByte(0) }, frame("EntityEvent") { writeInt(12); writeByte(3) }, frame("SetPassengers") { v(12); v(2); v(13); v(14) },
            frame("TakeItemEntity") { v(12); v(7); v(1) }, frame("SetEntityLink") { writeInt(12); writeInt(0) }, frame("RemoveEntities") { v(2); v(13); v(14) },
            frame("DamageEvent") { v(12); v(3); v(0); v(14); writeBoolean(false) }, frame("MountScreenOpen") { v(1); v(2); writeInt(12) },
        )
        frames.forEach { assertContentEquals(it, rw.toClient(it), "packet ${it[0]}") }
        val sb = sframe("Interact") { v(12); v(0); writeFloat(0f); writeFloat(0f); writeFloat(0f); writeBoolean(false) }
        assertContentEquals(sb, rw.toBackend(sb))
    }

    @Test fun `add entity gets a fresh client id and its projectile owner is mapped`() {
        val (m, rw) = mapped()
        val owner = rw.toClient(frame("Animate") { v(21); writeByte(0) })!!.let { r(it).rv() }
        assertTrue(owner >= EntityIdMap.FRESH_BASE)
        val arrow = frame("AddEntity") { v(30); writeBytes(ByteArray(16)); v(6); writeBytes(ByteArray(24)); writeByte(0); writeBytes(ByteArray(3)); v(21) }   // type 6 = arrow, data = owner id 21
        val out = r(rw.toClient(arrow)!!)
        val cid = out.rv(); assertNotEquals(30, cid); out.skipBytes(16); assertEquals(6, out.rv()); out.skipBytes(24 + 1 + 3)
        assertEquals(owner, out.rv(), "owner mapped to the same client id as the entity 21 has")
        assertEquals(cid, m.toClient(30)); assertEquals(6, m.typeOf(cid)); assertTrue(cid in m.heldIds())
        // a cow with data 21 (not a projectile): data is not an id
        val cow = frame("AddEntity") { v(31); writeBytes(ByteArray(16)); v(30); writeBytes(ByteArray(24)); writeByte(0); writeBytes(ByteArray(3)); v(21) }
        val o2 = r(rw.toClient(cow)!!); o2.rv(); o2.skipBytes(16); o2.rv(); o2.skipBytes(24 + 1 + 3); assertEquals(21, o2.rv())
    }

    @Test fun `add entity with a long movement vector keeps the data position`() {
        val (_, rw) = mapped()
        val f = frame("AddEntity") { v(40); writeBytes(ByteArray(16)); v(6); writeBytes(ByteArray(24)); writeByte(0x05); writeBytes(ByteArray(5)); v(300); writeBytes(ByteArray(3)); v(0) }
        val o = r(rw.toClient(f)!!); o.rv(); o.skipBytes(16); o.rv(); o.skipBytes(24); assertEquals(5, o.readUnsignedByte().toInt()); o.skipBytes(5); assertEquals(300, o.rv()); o.skipBytes(3); assertEquals(0, o.rv()); assertEquals(0, o.readableBytes())
    }

    @Test fun `the player's own id maps to the client's and back`() {
        val (m, rw) = mapped()
        val hp = rw.toClient(frame("SetEntityMotion") { v(55); writeBytes(ByteArray(6)) })!!
        assertEquals(7, r(hp).rv())
        val cmd = sframe("PlayerCommand") { v(7); v(0); v(0) }
        assertEquals(55, r(rw.toBackend(cmd)!!).rv())
        // an interaction with a client id the backend never told about is dropped, a known one is mapped back
        assertNull(rw.toBackend(sframe("Interact") { v(123456); v(0) })); assertEquals(1L, rw.droppedUnknown)
        val cid = r(rw.toClient(frame("Animate") { v(77); writeByte(0) })!!).rv()
        assertEquals(77, r(rw.toBackend(sframe("Interact") { v(cid); v(0) })!!).rv())
        assertEquals(77, r(rw.toBackend(sframe("Attack") { v(cid) })!!).rv())
        val tag = r(rw.toBackend(sframe("EntityTagQuery") { v(9); v(cid) })!!); assertEquals(9, tag.rv()); assertEquals(77, tag.rv())
        val sp = r(rw.toBackend(sframe("SpectatorAction") { v(cid + 1) })!!); assertEquals(78, sp.rv())
        assertEquals(0, r(rw.toBackend(sframe("SpectatorAction") { v(0) })!!).rv())
        assertEquals(55, m.backendSelf)
    }

    @Test fun `lists links damage events and sounds are mapped`() {
        val (m, rw) = mapped()
        val a = m.toClient(60); val b = m.toClient(61)
        val pass = r(rw.toClient(frame("SetPassengers") { v(60); v(2); v(61); v(55) })!!); assertEquals(a, pass.rv()); assertEquals(2, pass.rv()); assertEquals(b, pass.rv()); assertEquals(7, pass.rv())
        val link = r(rw.toClient(frame("SetEntityLink") { writeInt(60); writeInt(61) })!!); assertEquals(a, link.readInt()); assertEquals(b, link.readInt())
        val unlink = r(rw.toClient(frame("SetEntityLink") { writeInt(60); writeInt(0) })!!); unlink.readInt(); assertEquals(0, unlink.readInt())
        val dmg = r(rw.toClient(frame("DamageEvent") { v(60); v(4); v(62); v(0); writeBoolean(false) })!!); assertEquals(a, dmg.rv()); assertEquals(4, dmg.rv()); assertEquals(m.toClient(61) + 1, dmg.rv()); assertEquals(0, dmg.rv())
        val take = r(rw.toClient(frame("TakeItemEntity") { v(60); v(55); v(3) })!!); assertEquals(a, take.rv()); assertEquals(7, take.rv()); assertEquals(3, take.rv())
        val snd = r(rw.toClient(frame("SoundEntity") { v(5); v(1); v(61); writeFloat(1f); writeFloat(1f); writeLong(0) })!!); assertEquals(5, snd.rv()); assertEquals(1, snd.rv()); assertEquals(b, snd.rv())
        val sndDirect = r(rw.toClient(frame("SoundEntity") { v(0); str("x:y"); writeBoolean(true); writeFloat(2f); v(2); v(61); writeFloat(1f); writeFloat(1f); writeLong(0) })!!)
        assertEquals(0, sndDirect.rv()); sndDirect.skipBytes(1 + 3); assertTrue(sndDirect.readBoolean()); sndDirect.readFloat(); assertEquals(2, sndDirect.rv()); assertEquals(b, sndDirect.rv())
        val look = r(rw.toClient(frame("PlayerLookAt") { v(0); repeat(3) { writeDouble(1.0) }; writeBoolean(true); v(60); v(1) })!!); look.rv(); look.skipBytes(24); assertTrue(look.readBoolean()); assertEquals(a, look.rv())
        val mount = r(rw.toClient(frame("MountScreenOpen") { v(3); v(5); writeInt(60) })!!); mount.rv(); mount.rv(); assertEquals(a, mount.readInt())
        val ev = r(rw.toClient(frame("EntityEvent") { writeInt(60); writeByte(9) })!!); assertEquals(a, ev.readInt())
        val rem = r(rw.toClient(frame("RemoveEntities") { v(2); v(60); v(61) })!!); assertEquals(2, rem.rv()); assertEquals(a, rem.rv()); assertEquals(b, rem.rv())
        assertFalse(a in m.heldIds())
    }

    @Test fun `debug entity values never reach a client with its own id space`() {
        val (_, rw) = mapped(); assertNull(rw.toClient(frame("DebugEntityValue") { v(60) }))
    }

    @Test fun `every rewritten packet type of the clientbound table moves its first id`() {
        val (m, rw) = mapped()
        for (n in listOf("Animate", "BlockDestruction", "EntityPositionSync", "HurtAnimation", "MoveEntityPos", "MoveEntityPosRot", "MoveEntityRot", "MoveMinecartAlongTrack", "ProjectilePower", "RotateHead", "SetCamera",
            "SetEntityMotion", "SetEquipment", "SwingAnimation", "TeleportEntity", "UpdateAttributes", "UpdateMobEffect", "RemoveMobEffect", "PlayerCombatKill", "SetEntityData")) {
            val out = r(rw.toClient(frame(n) { v(90); writeBytes(ByteArray(8)) })!!)
            assertEquals(m.toClient(90), out.rv(), n)
        }
    }

    @Test fun `ids are never reused after a transplant`() {
        val m = EntityIdMap().also { it.start(7); it.transplantTo(55) }
        val first = m.toClient(60); m.transplantTo(56)
        val second = m.toClient(60); assertNotEquals(first, second); assertNull(m.toBackend(first))
    }
}

/** The packet classes of entity-id-fields.txt that are ids (not NOT). */
object EntityIdTableTestAccess {
    fun entityPackets(): Set<String> = EntityIdTableTestAccess::class.java.getResourceAsStream("/entity-id-fields.txt")!!.bufferedReader().readLines()
        .filter { it.startsWith("C ") || it.startsWith("S ") }.map { it.split(' ')[1] }.toSet()
}
