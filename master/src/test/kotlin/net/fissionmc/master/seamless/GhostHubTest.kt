package net.fissionmc.master.seamless

import com.github.retrooper.packetevents.protocol.ConnectionState
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.util.EventCreationUtil
import com.github.retrooper.packetevents.wrapper.play.server.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import net.fissionmc.master.client.Limbo
import net.fissionmc.master.client.TabEntry
import net.fissionmc.master.client.VarInts
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.PacketTap
import net.fissionmc.packets.Side
import net.fissionmc.protocol.GhostMeta
import net.fissionmc.protocol.GhostPose
import net.fissionmc.protocol.GhostSwing
import java.util.UUID
import kotlin.test.*

class GhostHubTest {
    init { FissionPacketEventsAPI.install(Side.MASTER) }
    private val tap = PacketTap.create(EmbeddedChannel(), UUID(0, 1), "T", start = ConnectionState.PLAY)!!
    private fun ev(f: ByteArray): PacketSendEvent = EventCreationUtil.createSendEvent(EmbeddedChannel(), tap.user, null, Unpooled.wrappedBuffer(f), false)

    private class V(val name: String, override var ghostNode: String?) : GhostViewer {
        override val ghostUuid: UUID = UUID.nameUUIDFromBytes(name.toByteArray())
        val sent = ArrayList<ByteArray>(); val added = ArrayList<Int>(); val removed = ArrayList<Int>()
        override fun ghostSend(frame: ByteArray) { sent += frame }
        override fun ghostAdded(clientId: Int) { added += clientId }
        override fun ghostRemoved(clientId: Int) { removed += clientId }
        private val noise = setOf(PlayPacketIds.c("PlayerInfoUpdate"), PlayPacketIds.c("PlayerInfoRemove"), PlayPacketIds.c("SetPlayerTeam"))
        /** The entity frames (the tab line / team frames that surround a spawn or removal are checked in their own test). */
        fun core() = sent.filter { Limbo.idOf(it) !in noise }
        fun ids() = core().map { Limbo.idOf(it) }
    }
    private fun hub(known: () -> Boolean = { true }) = GhostHub(64.0, { u -> if (known()) TabEntry(u, "P" + u.toString().take(4), listOf(TextureProperty("textures", "skin", null))) else null })
    private fun pose(v: V, x: Double, z: Double, dim: String = "minecraft:overworld", yaw: Float = 0f, head: Float = 0f) =
        GhostPose(v.ghostUuid.mostSignificantBits, v.ghostUuid.leastSignificantBits, dim, x, 64.0, z, yaw, 0f, head, true)
    private fun meta(v: V, data: Int = 0x55, equip: Boolean = true) = GhostMeta(v.ghostUuid.mostSignificantBits, v.ghostUuid.leastSignificantBits, byteArrayOf(data.toByte(), -1), if (equip) byteArrayOf(0, 1, 2, 3) else ByteArray(0))
    private fun setup(hub: GhostHub, vararg vs: V) { vs.forEach { hub.register(it) } }
    private val spawnId by lazy { PlayPacketIds.c("AddEntity") }
    private val removeId by lazy { PlayPacketIds.c("RemoveEntities") }
    private val syncId by lazy { PlayPacketIds.c("EntityPositionSync") }

    @Test fun `a player of another backend within range spawns with data equipment and head, once`() {
        val hub = hub(); val a = V("Alice", "a"); val b = V("Bob", "b"); setup(hub, a, b)
        hub.meta(meta(b)); hub.pose(pose(b, 10.5, 20.5, yaw = 45f, head = 50f)); hub.pose(pose(a, 0.0, 0.0))
        assertEquals(listOf(spawnId, PlayPacketIds.c("SetEntityData"), PlayPacketIds.c("SetEquipment"), PlayPacketIds.c("RotateHead")), a.ids())
        val w = WrapperPlayServerSpawnEntity(ev(a.core()[0]))
        assertEquals(EntityTypes.PLAYER, w.entityType); assertEquals(GhostHub.ghostUuidOf(b.ghostUuid), w.uuid.get(), "the entity carries the derived uuid, never the real one"); assertEquals(10.5, w.position.x); assertEquals(20.5, w.position.z)
        assertEquals(GhostHub.GHOST_BASE, w.entityId); assertEquals(listOf(GhostHub.GHOST_BASE), a.added)
        // payloads are prefixed with the client id
        val d = Unpooled.wrappedBuffer(a.core()[1]); VarInts.tryRead(d); assertEquals(GhostHub.GHOST_BASE, VarInts.tryRead(d)); assertEquals(0x55, d.readByte().toInt())
        assertTrue(b.core().isEmpty() || b.core().all { Limbo.idOf(it) in listOf(spawnId, PlayPacketIds.c("SetEntityData"), PlayPacketIds.c("SetEquipment"), PlayPacketIds.c("RotateHead")) }, "bob sees alice as a ghost too (she is in range)")
        // same pose again: nothing new
        val n = a.sent.size; hub.pose(pose(b, 10.5, 20.5, yaw = 45f, head = 50f)); assertEquals(n, a.sent.size)
    }

    @Test fun `a ghost comes with its own unlisted tab line and the no-collision team, and leaves with them`() {
        val hub = hub(); val a = V("Alice", "a"); val b = V("Bob", "b"); setup(hub, a, b)
        hub.meta(meta(b)); hub.pose(pose(a, 0.0, 0.0)); hub.pose(pose(b, 10.0, 0.0))
        val ids = a.sent.map { Limbo.idOf(it) }
        assertEquals(listOf(PlayPacketIds.c("PlayerInfoUpdate"), PlayPacketIds.c("SetPlayerTeam"), spawnId), ids.take(3), "tab line and team before the entity")
        val g = GhostHub.ghostUuidOf(b.ghostUuid)
        val info = WrapperPlayServerPlayerInfoUpdate(ev(a.sent[0])).entries.single()
        assertEquals(g, info.profileId); assertFalse(info.isListed); assertEquals("skin", info.gameProfile.textureProperties.single().value)
        val team = WrapperPlayServerTeams(ev(a.sent[1]))
        assertEquals(GhostFrames.TEAM, team.teamName); assertEquals(WrapperPlayServerTeams.TeamMode.CREATE, team.teamMode)
        assertEquals(WrapperPlayServerTeams.CollisionRule.NEVER, team.teamInfo.get().collisionRule); assertEquals(listOf(info.gameProfile.name), team.players.toList())
        a.sent.clear(); hub.pose(pose(b, 500.0, 0.0))
        assertEquals(listOf(removeId, PlayPacketIds.c("PlayerInfoRemove"), PlayPacketIds.c("SetPlayerTeam")), a.sent.map { Limbo.idOf(it) })
        assertEquals(listOf(g), WrapperPlayServerPlayerInfoRemove(ev(a.sent[1])).profileIds)
        assertEquals(WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES, WrapperPlayServerTeams(ev(a.sent[2])).teamMode)
        // the second ghost joins the existing team instead of creating it again (a duplicate create would be an error on the client)
        a.sent.clear(); hub.pose(pose(b, 10.0, 0.0))
        assertEquals(WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, WrapperPlayServerTeams(ev(a.sent[1])).teamMode)
        // a reset (the client lost its scoreboard) creates the team again
        hub.reset(a); a.sent.clear(); hub.placed(a)
        assertEquals(WrapperPlayServerTeams.TeamMode.CREATE, WrapperPlayServerTeams(ev(a.sent[1])).teamMode)
    }

    @Test fun `a ghost and the native entity of the same player never share a uuid, so neither hides the other`() {
        val hub = hub(); val a = V("Alice", "a"); val b = V("Bob", "b"); setup(hub, a, b)
        hub.meta(meta(b)); hub.pose(pose(a, 0.0, 0.0)); hub.pose(pose(b, 10.0, 0.0))
        val ghost = WrapperPlayServerSpawnEntity(ev(a.core()[0])).uuid.get()
        assertNotEquals(b.ghostUuid, ghost)
        // Bob walks onto Alice's backend: the ghost is removed by ITS id, the native entity (real uuid, backend id) is the backend's own business
        a.sent.clear(); b.ghostNode = "a"; hub.placed(b)
        assertEquals(listOf(removeId), a.ids()); assertEquals(GhostHub.GHOST_BASE, WrapperPlayServerDestroyEntities(ev(a.core()[0])).entityIds.single())
        // and back: a fresh ghost with the same derived uuid but a fresh id
        a.sent.clear(); b.ghostNode = "b"; hub.placed(b)
        assertEquals(ghost, WrapperPlayServerSpawnEntity(ev(a.core()[0])).uuid.get()); assertEquals(GhostHub.GHOST_BASE + 1, WrapperPlayServerSpawnEntity(ev(a.core()[0])).entityId)
    }

    @Test fun `no ghost is spawned on top of the viewer, it appears once the player moved off`() {
        val hub = hub(); val a = V("Alice", "a"); val b = V("Bob", "b"); setup(hub, a, b)
        hub.meta(meta(b)); hub.pose(pose(a, 0.0, 0.0)); hub.pose(pose(b, 0.5, 0.5)); assertTrue(a.sent.isEmpty())
        hub.pose(pose(b, 3.0, 0.5)); assertEquals(spawnId, a.ids().first())
    }

    @Test fun `players on the same backend are never ghosts`() {
        val hub = hub(); val a = V("Alice", "a"); val a2 = V("Amy", "a"); setup(hub, a, a2)
        hub.meta(meta(a2)); hub.pose(pose(a2, 1.0, 1.0)); hub.pose(pose(a, 0.0, 0.0))
        assertTrue(a.sent.isEmpty())
    }

    @Test fun `moves become position syncs and head looks, and range plus hysteresis decides`() {
        val hub = hub(); val a = V("Alice", "a"); val b = V("Bob", "b"); setup(hub, a, b)
        hub.meta(meta(b)); hub.pose(pose(a, 0.0, 0.0)); hub.pose(pose(b, 60.0, 0.0)); a.sent.clear()
        hub.pose(pose(b, 61.0, 0.0, yaw = 10f, head = 12f))
        assertEquals(listOf(syncId, PlayPacketIds.c("RotateHead")), a.ids())
        val s = WrapperPlayServerEntityPositionSync(ev(a.core()[0])); assertEquals(GhostHub.GHOST_BASE, s.id); assertEquals(61.0, s.position.endPosition.x); assertEquals(10f, s.yRot)
        a.sent.clear(); hub.pose(pose(b, 70.0, 0.0))                 // 70 > 64 but < 64 + 16: still shown (moves)
        assertEquals(syncId, a.ids().first())
        a.sent.clear(); hub.pose(pose(b, 90.0, 0.0))                 // > 80: removed
        assertEquals(listOf(removeId), a.ids()); assertEquals(listOf(GhostHub.GHOST_BASE), a.removed)
        a.sent.clear(); hub.pose(pose(b, 70.0, 0.0))                 // 70 > 64 and not shown: stays away
        assertTrue(a.sent.isEmpty())
        hub.pose(pose(b, 40.0, 0.0)); assertEquals(spawnId, a.ids().first())
        assertEquals(GhostHub.GHOST_BASE + 1, WrapperPlayServerSpawnEntity(ev(a.core()[0])).entityId, "a fresh id, never reused")
    }

    @Test fun `another dimension is no ghost and a dimension change removes it`() {
        val hub = hub(); val a = V("Alice", "a"); val b = V("Bob", "b"); setup(hub, a, b)
        hub.meta(meta(b)); hub.pose(pose(a, 0.0, 0.0)); hub.pose(pose(b, 5.0, 5.0, "minecraft:the_nether")); assertTrue(a.sent.isEmpty())
        hub.pose(pose(b, 5.0, 5.0)); assertEquals(spawnId, a.ids().first()); a.sent.clear()
        hub.pose(pose(b, 5.0, 5.0, "minecraft:the_end")); assertEquals(listOf(removeId), a.ids())
    }

    @Test fun `a ghost waits for the tab line and spawns when it is shown`() {
        var holds = false
        val hub = hub { holds }; val a = V("Alice", "a"); val b = V("Bob", "b"); setup(hub, a, b)
        hub.meta(meta(b)); hub.pose(pose(a, 0.0, 0.0)); hub.pose(pose(b, 5.0, 5.0)); assertTrue(a.sent.isEmpty())
        holds = true; hub.tabShown(a.ghostUuid, b.ghostUuid); assertEquals(spawnId, a.ids().first())
    }

    @Test fun `the ghost disappears when the player moves to the viewer's backend, leaves or the viewer is reset`() {
        val hub = hub(); val a = V("Alice", "a"); val b = V("Bob", "b"); val c = V("Cid", "c"); setup(hub, a, b, c)
        for (v in listOf(a, b, c)) hub.meta(meta(v))
        hub.pose(pose(a, 0.0, 0.0)); hub.pose(pose(b, 5.0, 5.0)); hub.pose(pose(c, 6.0, 6.0))
        assertEquals(2, hub.ghostsOf(a.ghostUuid).size)
        a.sent.clear(); b.ghostNode = "a"; hub.placed(b)       // Bob switched to backend a: native for Alice now
        assertEquals(listOf(removeId), a.ids()); assertEquals(1, hub.ghostsOf(a.ghostUuid).size)
        a.sent.clear(); hub.unregister(c); assertEquals(listOf(removeId), a.ids()); assertTrue(hub.ghostsOf(a.ghostUuid).isEmpty())
        // a transplant: the client dropped its entities, the hub forgets and respawns on placement
        c.ghostNode = "c"; hub.register(c); hub.meta(meta(c)); hub.pose(pose(c, 6.0, 6.0)); a.sent.clear()
        hub.reset(a); assertTrue(hub.ghostsOf(a.ghostUuid).isEmpty()); hub.placed(a); assertEquals(spawnId, a.ids().first())
    }

    @Test fun `metadata changes and swings reach only viewers who hold the ghost`() {
        val hub = hub(); val a = V("Alice", "a"); val b = V("Bob", "b"); val far = V("Far", "c"); setup(hub, a, b, far)
        hub.meta(meta(b)); hub.pose(pose(a, 0.0, 0.0)); hub.pose(pose(b, 5.0, 5.0)); hub.pose(pose(far, 1000.0, 1000.0))
        a.sent.clear(); far.sent.clear()
        hub.meta(meta(b, data = 0x11, equip = false)); assertEquals(listOf(PlayPacketIds.c("SetEntityData")), a.ids())
        hub.swing(GhostSwing(b.ghostUuid.mostSignificantBits, b.ghostUuid.leastSignificantBits, 0))
        assertEquals(PlayPacketIds.c("SwingAnimation"), a.ids().last()); assertTrue(far.sent.isEmpty())
        val an = WrapperPlayServerSwingAnimation(ev(a.core().last())); assertEquals(GhostHub.GHOST_BASE, an.entityId)
    }

    @Test fun `ghost ids stay clear of backend entity ids and a serverbound packet naming a ghost is dropped`() {
        val map = EntityIdMap().also { it.start(7) }
        val rw = EntityRewriter(map)
        val interact = Unpooled.buffer().also { VarInts.write(it, PlayPacketIds.s("Interact")); VarInts.write(it, GhostHub.GHOST_BASE + 3); it.writeByte(1) }.let { ByteArray(it.readableBytes()).also { a -> it.readBytes(a) } }
        assertNull(rw.toBackend(interact))
        val normal = Unpooled.buffer().also { VarInts.write(it, PlayPacketIds.s("Interact")); VarInts.write(it, 99); it.writeByte(1) }.let { ByteArray(it.readableBytes()).also { a -> it.readBytes(a) } }
        assertContentEquals(normal, rw.toBackend(normal))
        assertTrue(GhostHub.GHOST_BASE > EntityIdMap.FRESH_BASE * 1000)
    }
}
