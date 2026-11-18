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
        // (it stays while the native one has not been sent to Alice yet, so Bob never vanishes in between; the session reports the native AddEntity)
        a.sent.clear(); b.ghostNode = "a"; hub.placed(b)
        assertTrue(a.ids().isEmpty(), "the ghost waits for the real Bob")
        hub.realPlayerArrived(a, b.ghostUuid)
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
        a.sent.clear(); b.ghostNode = "a"; hub.placed(b)       // Bob switched to backend a: native for Alice now, once the real entity is there
        assertTrue(a.ids().isEmpty()); assertEquals(2, hub.ghostsOf(a.ghostUuid).size, "the ghost waits for the real Bob")
        hub.realPlayerArrived(a, b.ghostUuid)
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

    private fun addPayload(entityId: Int, uuid: UUID): ByteArray {
        val frame = Limbo.encode(WrapperPlayServerSpawnEntity(entityId, uuid, EntityTypes.PIG, com.github.retrooper.packetevents.protocol.world.Location(com.github.retrooper.packetevents.util.Vector3d(1.0, 64.0, 1.0), 0f, 0f), 0f, 0, null))
        val b = Unpooled.wrappedBuffer(frame); VarInts.tryRead(b)
        return ByteArray(b.readableBytes()).also { b.readBytes(it) }
    }
    private fun eg(u: UUID, x: Double, z: Double, flags: Int, add: ByteArray = ByteArray(0), data: ByteArray = ByteArray(0)) =
        net.fissionmc.protocol.EntityGhost(u.mostSignificantBits, u.leastSignificantBits, x, 64.0, z, 10f, 0f, 10f, true, flags, add, data, ByteArray(0))
    private fun batch(node: String, ups: List<net.fissionmc.protocol.EntityGhost>, gone: List<UUID> = emptyList()) =
        net.fissionmc.protocol.EntityGhostBatch(node, "minecraft:overworld", ups, gone.flatMap { listOf(it.mostSignificantBits, it.leastSignificantBits) })

    @Test fun `an entity of another backend near a viewer is spawned with the ghost id and uuid, moved and removed`() {
        val hub = hub(); val a = V("Alice", "a"); setup(hub, a); hub.pose(pose(a, 0.0, 0.0))
        val mob = UUID(7, 7)
        hub.entityBatch(batch("b", listOf(eg(mob, 10.0, 10.0, 7, addPayload(321, mob), byteArrayOf(1, 2)))))
        val spawn = a.core().first { Limbo.idOf(it) == spawnId }
        val w = WrapperPlayServerSpawnEntity(ev(spawn))
        assertEquals(GhostHub.ENTITY_BASE, w.entityId); assertEquals(GhostHub.entityGhostUuidOf(mob), w.uuid.get())
        assertEquals(listOf(GhostHub.ENTITY_BASE), a.added)
        val n = a.sent.size
        hub.entityBatch(batch("b", listOf(eg(mob, 11.0, 10.0, 0))))
        assertEquals(syncId, Limbo.idOf(a.sent[n])); assertEquals(n + 1, a.sent.size)
        hub.entityBatch(batch("b", emptyList(), listOf(mob)))
        assertEquals(removeId, Limbo.idOf(a.sent.last())); assertEquals(listOf(GhostHub.ENTITY_BASE), a.removed)
    }

    @Test fun `the ghosts of a backend that only sends heartbeats stay, those of a silent one go after the expiry`() {
        var now = 1_000_000L
        val hub = GhostHub(64.0, { null }, {}, 30_000L, { now })
        val a = V("Alice", "a"); setup(hub, a); hub.pose(pose(a, 0.0, 0.0))
        val mob = UUID(9, 9)
        hub.entityBatch(batch("b", listOf(eg(mob, 10.0, 10.0, 7, addPayload(321, mob)))))
        assertEquals(1, hub.entityGhostCount)
        // a slow (1 tps) backend and a busy one: b sends an empty heartbeat every 2 s of real time, c talks all the time
        repeat(20) { now += 2_000; hub.entityBatch(batch("b", emptyList())); hub.entityBatch(batch("c", emptyList())) }
        assertEquals(1, hub.entityGhostCount, "40 s with heartbeats: nothing expires (the old 10 s limit with 40 ticks = 40 s at 1 tps dropped it)")
        now += 31_000; hub.entityBatch(batch("c", emptyList()))
        assertEquals(0, hub.entityGhostCount, "no word from b for 31 s: dropped")
    }

    @Test fun `after a handover the new owner keeps the ghost and the old owner's removal is ignored`() {
        val hub = hub(); val c = V("Carol", "c"); setup(hub, c); hub.pose(pose(c, 0.0, 0.0))
        val mob = UUID(8, 8)
        hub.entityBatch(batch("a", listOf(eg(mob, 10.0, 10.0, 7, addPayload(321, mob)))))
        assertEquals(listOf(GhostHub.ENTITY_BASE), c.added)
        hub.entityBatch(batch("b", listOf(eg(mob, 10.0, 10.0, 7, addPayload(321, mob)))))   // the new owner reports it
        hub.entityBatch(batch("a", emptyList(), listOf(mob)))                               // the old owner's "gone" after the handover
        assertTrue(c.removed.isEmpty(), "the ghost must survive the old owner's removal")
        hub.entityBatch(batch("a", listOf(eg(mob, 11.0, 10.0, 0))))                          // a late movement of the old owner is not an update either
        hub.entityBatch(batch("b", emptyList(), listOf(mob)))
        assertEquals(listOf(GhostHub.ENTITY_BASE), c.removed, "the current owner can remove it")
    }

    @Test fun `entities of the viewer's own backend, far away or in another dimension are not shown`() {
        val hub = hub(); val a = V("Alice", "a"); setup(hub, a); hub.pose(pose(a, 0.0, 0.0))
        hub.entityBatch(batch("a", listOf(eg(UUID(1, 1), 5.0, 5.0, 7, addPayload(1, UUID(1, 1))))))
        hub.entityBatch(batch("b", listOf(eg(UUID(2, 2), 500.0, 5.0, 7, addPayload(2, UUID(2, 2))))))
        hub.entityBatch(net.fissionmc.protocol.EntityGhostBatch("b", "minecraft:the_nether", listOf(eg(UUID(3, 3), 5.0, 5.0, 7, addPayload(3, UUID(3, 3)))), emptyList()))
        assertTrue(a.core().isEmpty(), "nothing should have been sent")
        hub.entityBatch(batch("b", listOf(eg(UUID(2, 2), 20.0, 5.0, 0))))
        assertEquals(listOf(spawnId, syncId), a.ids(), "an entity that comes into range is spawned, then moved to where it is now (the cached add holds its first pose)")
    }

    @Test fun `an item ghost within the pickup box asks its owner once, a far one does not`() {
        val hub = hub(); val a = V("Alice", "a"); setup(hub, a); hub.pose(pose(a, 0.0, 0.0))
        val asked = ArrayList<Triple<String, UUID, UUID>>()
        hub.pickupAsk = { node, entity, player, _ -> asked += Triple(node, entity, player) }
        val near = UUID(8, 8); val far = UUID(9, 9)
        hub.entityBatch(batch("b", listOf(eg(near, 1.0, 1.0, 7 or 8, addPayload(1, near)), eg(far, 6.0, 0.0, 7 or 8, addPayload(2, far)))))
        assertEquals(listOf(Triple("b", near, a.ghostUuid)), asked)
        hub.entityBatch(batch("b", listOf(eg(near, 1.0, 1.0, 8))))
        assertEquals(1, asked.size, "asked again within 500 ms")
    }

    @Test fun `a transfer shows the entities of the other backend in one go and keeps them until the real ones arrive`() {
        val hub = hub(); val a = V("Alice", "a"); setup(hub, a); hub.pose(pose(a, 0.0, 0.0))
        val aMob = UUID(5, 5); val bMob = UUID(6, 6)
        hub.entityBatch(batch("a", listOf(eg(aMob, 3.0, 3.0, 7, addPayload(1, aMob)))))   // not shown: same backend as the viewer
        hub.entityBatch(batch("b", listOf(eg(bMob, 4.0, 4.0, 7, addPayload(2, bMob)))))
        assertEquals(listOf(spawnId), a.ids())
        // the viewer moves to backend b: b's mob must stay (waiting for the real one), a's mob is shown at once
        a.ghostNode = "b"; hub.reset(a)
        val frames = hub.entityFramesFor(a, "b")
        assertEquals(1, frames.count { Limbo.idOf(it) == spawnId }, "only the entity of the other backend is re-shown")
        val aGhost = WrapperPlayServerSpawnEntity(ev(frames.first { Limbo.idOf(it) == spawnId }))
        assertEquals(GhostHub.entityGhostUuidOf(aMob), aGhost.uuid.get())
        hub.entityBatch(batch("b", listOf(eg(bMob, 4.5, 4.0, 0))))
        assertNull(hub.realArrived(a, bMob), "b's mob was not re-shown, nothing to remove")
        assertNotNull(hub.realArrived(a, aMob), "the real entity arrived: its ghost is removed")
        assertNull(hub.realArrived(a, aMob))
    }

    private fun links(node: String, vararg pairs: Pair<UUID, UUID>) =
        net.fissionmc.protocol.EntityGhostLinks(node, "minecraft:overworld", pairs.flatMap { listOf(it.first.mostSignificantBits, it.first.leastSignificantBits, it.second.mostSignificantBits, it.second.leastSignificantBits) })
    private fun linkFrames(v: V) = v.core().filter { Limbo.idOf(it) == PlayPacketIds.c("SetEntityLink") }.map { val b = Unpooled.wrappedBuffer(it); VarInts.tryRead(b); b.readInt() to b.readInt() }

    @Test fun `the link frame is the vanilla layout of SetEntityLink`() {
        val f = GhostFrames.link(1_600_000_001, 1_600_000_000)
        val b = Unpooled.wrappedBuffer(f); assertEquals(PlayPacketIds.c("SetEntityLink"), VarInts.tryRead(b))
        assertEquals(1_600_000_001, b.readInt()); assertEquals(1_600_000_000, b.readInt()); assertEquals(0, b.readableBytes())
    }

    @Test fun `llamas leashed to a ghost trader are linked once both are ghosts, and freed when the trader goes`() {
        val hub = hub(); val a = V("Alice", "a"); setup(hub, a); hub.pose(pose(a, 0.0, 0.0))
        val trader = UUID(20, 20); val llama = UUID(21, 21)
        hub.entityBatch(batch("b", listOf(eg(llama, 3.0, 3.0, 7, addPayload(2, llama)), eg(trader, 4.0, 4.0, 7, addPayload(1, trader)))))
        assertTrue(linkFrames(a).isEmpty(), "no leash known yet")
        hub.entityLinks(links("b", llama to trader))
        val llamaId = hub.entityGhostsOf(a.ghostUuid)[llama]!!; val traderId = hub.entityGhostsOf(a.ghostUuid)[trader]!!
        assertEquals(listOf(llamaId to traderId), linkFrames(a))
        hub.entityLinks(links("b", llama to trader)); assertEquals(1, linkFrames(a).size, "an unchanged snapshot sends nothing")
        hub.entityBatch(batch("b", emptyList(), listOf(trader)))   // the trader's ghost goes: the llama must be released first-hand, not left tied to a removed id
        assertEquals(listOf(llamaId to traderId, llamaId to 0), linkFrames(a))
        hub.entityLinks(links("b")); assertEquals(2, linkFrames(a).size)
    }

    @Test fun `a link whose holder ghost is not shown to the viewer is not sent, and appears when the holder does`() {
        val hub = hub(); val a = V("Alice", "a"); setup(hub, a); hub.pose(pose(a, 0.0, 0.0))
        val trader = UUID(30, 30); val llama = UUID(31, 31)
        hub.entityBatch(batch("b", listOf(eg(llama, 3.0, 3.0, 7, addPayload(2, llama)), eg(trader, 500.0, 4.0, 7, addPayload(1, trader)))))
        hub.entityLinks(links("b", llama to trader))
        assertTrue(linkFrames(a).isEmpty(), "the trader is out of range")
        hub.entityBatch(batch("b", listOf(eg(trader, 5.0, 4.0, 0))))
        assertEquals(1, linkFrames(a).size)
    }

    @Test fun `a transfer re-shows the leashes with the entities`() {
        val hub = hub(); val a = V("Alice", "a"); setup(hub, a); hub.pose(pose(a, 0.0, 0.0))
        val trader = UUID(40, 40); val llama = UUID(41, 41)
        hub.entityBatch(batch("b", listOf(eg(llama, 3.0, 3.0, 7, addPayload(2, llama)), eg(trader, 4.0, 4.0, 7, addPayload(1, trader)))))
        hub.entityLinks(links("b", llama to trader))
        a.ghostNode = "c"; hub.reset(a)
        val frames = hub.entityFramesFor(a, "c")
        assertEquals(1, frames.count { Limbo.idOf(it) == PlayPacketIds.c("SetEntityLink") })
    }

    private fun removedIds(f: ByteArray): List<Int> { val b = Unpooled.wrappedBuffer(f); VarInts.tryRead(b); return List(VarInts.tryRead(b)!!) { VarInts.tryRead(b)!! } }

    @Test fun `a transplant keeps the ghost of an entity the new backend owns until its real copy arrives, and shows the old backend's entities at once`() {
        var now = 1_000_000L
        val hub = GhostHub(64.0, { null }, {}, 30_000L, { now }); val a = V("Alice", "a"); setup(hub, a); hub.pose(pose(a, 0.0, 0.0))
        val aMob = UUID(5, 5); val bMob = UUID(6, 6)
        hub.entityBatch(batch("a", listOf(eg(aMob, 3.0, 3.0, 7, addPayload(1, aMob)))))
        hub.entityBatch(batch("b", listOf(eg(bMob, 4.0, 4.0, 7, addPayload(2, bMob)))))
        val bGhost = hub.entityGhostsOf(a.ghostUuid)[bMob]!!
        a.ghostNode = "b"
        val t = hub.transplant(a, "b")
        assertEquals(setOf(bGhost), t.keep, "b's mob stays: its ghost is not removed by the cleanup")
        assertEquals(1, t.frames.count { Limbo.idOf(it) == spawnId }, "a's mob (real until now) is shown as a ghost in the same bundle")
        assertEquals(GhostHub.entityGhostUuidOf(aMob), WrapperPlayServerSpawnEntity(ev(t.frames.first { Limbo.idOf(it) == spawnId })).uuid.get())
        a.sent.clear()
        hub.placed(a); hub.entityBatch(batch("b", listOf(eg(bMob, 4.5, 4.0, 0))))
        assertTrue(a.core().none { Limbo.idOf(it) == removeId }, "the ghost of b's mob is still there while the real one has not arrived")
        assertEquals(bGhost, hub.realArrived(a, bMob), "the real entity arrived: the caller removes the ghost")
        // never arrives: the ghost goes after the wait
        val cMob = UUID(7, 7); val v2 = V("Carol", "a"); setup(hub, v2); hub.pose(pose(v2, 0.0, 0.0))
        hub.entityBatch(batch("b", listOf(eg(cMob, 6.0, 4.0, 7, addPayload(3, cMob)))))
        v2.ghostNode = "b"; hub.transplant(v2, "b"); v2.sent.clear()
        now += GhostHub.REAL_WAIT_MS + 1; hub.entityBatch(batch("b", listOf(eg(cMob, 6.5, 4.0, 0))))
        assertEquals(removeId, Limbo.idOf(v2.sent.last()), "nothing came: the ghost does not stay for ever")
    }

    @Test fun `a ghost that is spawned later appears at the entity's current pose, not where it was first reported`() {
        val hub = hub(); val a = V("Alice", "a"); setup(hub, a); hub.pose(pose(a, 0.0, 0.0))
        val mob = UUID(11, 11)
        hub.entityBatch(batch("b", listOf(eg(mob, 500.0, 10.0, 7, addPayload(321, mob)))))    // far: not shown yet
        hub.entityBatch(batch("b", listOf(eg(mob, 12.0, 10.0, 0))))                              // it walked into range: spawned from the cached AddEntity, which holds the first pose
        assertEquals(listOf(spawnId, syncId), a.ids().take(2), "the add is followed by a position frame")
        val s = WrapperPlayServerEntityPositionSync(ev(a.core()[1])); assertEquals(GhostHub.ENTITY_BASE, s.id); assertEquals(12.0, (s.position as com.github.retrooper.packetevents.protocol.vector.positionpath.LinearPositionPath).endPosition.x)
        // after a transplant the re-shown ghosts get the same treatment
        val v2 = V("Bob", "b"); setup(hub, v2); hub.pose(pose(v2, 0.0, 0.0)); v2.ghostNode = "c"
        val t = hub.transplant(v2, "c"); assertEquals(listOf(spawnId, syncId), t.frames.map { Limbo.idOf(it) }.filter { it == spawnId || it == syncId })
    }

    @Test fun `a transplant keeps a player ghost, re-sends its tab line and team, and removes it when the real player arrives`() {
        val hub = hub(); val a = V("Alice", "a"); val b = V("Bob", "b"); setup(hub, a, b)
        hub.meta(meta(b)); hub.pose(pose(a, 0.0, 0.0)); hub.pose(pose(b, 10.0, 0.0))
        val ghostId = hub.ghostsOf(a.ghostUuid)[b.ghostUuid]!!
        a.ghostNode = "b"; a.sent.clear()
        val t = hub.transplant(a, "b")
        assertEquals(setOf(ghostId), t.keep)
        assertEquals(listOf(PlayPacketIds.c("PlayerInfoUpdate"), PlayPacketIds.c("SetPlayerTeam")), t.frames.map { Limbo.idOf(it) }, "tab line and team come back, the entity stays")
        assertEquals(WrapperPlayServerTeams.TeamMode.CREATE, WrapperPlayServerTeams(ev(t.frames[1])).teamMode)
        hub.placed(a)
        assertTrue(a.sent.isEmpty(), "the viewer is now on bob's backend, but the ghost stays until the real bob shows up")
        hub.realPlayerArrived(a, b.ghostUuid)
        assertEquals(listOf(removeId, PlayPacketIds.c("PlayerInfoRemove"), PlayPacketIds.c("SetPlayerTeam")), a.sent.map { Limbo.idOf(it) })
        assertEquals(listOf(ghostId), a.removed)
    }

    @Test fun `a transplant keeps a player ghost of a third backend untouched and drops one that went out of range`() {
        val hub = hub(); val a = V("Alice", "a"); val c = V("Cara", "c"); val d = V("Dan", "c"); setup(hub, a, c, d)
        hub.meta(meta(c)); hub.meta(meta(d)); hub.pose(pose(a, 0.0, 0.0)); hub.pose(pose(c, 10.0, 0.0)); hub.pose(pose(d, 20.0, 0.0))
        val cId = hub.ghostsOf(a.ghostUuid)[c.ghostUuid]!!
        hub.pose(pose(d, 5000.0, 0.0))   // dan leaves range after the removal was decided... (his ghost is gone already)
        a.ghostNode = "b"
        val t = hub.transplant(a, "b")
        assertEquals(setOf(cId), t.keep)
        hub.placed(a); hub.pose(pose(c, 11.0, 0.0))
        assertEquals(syncId, Limbo.idOf(a.sent.last()), "still shown and updated after the transplant: cara moved")
    }
}
