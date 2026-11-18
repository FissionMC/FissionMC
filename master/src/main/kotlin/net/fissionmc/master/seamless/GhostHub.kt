package net.fissionmc.master.seamless

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.protocol.vector.positionpath.LinearPositionPath
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSwingAnimation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.netty.buffer.Unpooled
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import net.fissionmc.master.client.Limbo
import net.fissionmc.master.client.TabEntry
import net.fissionmc.master.client.VarInts
import net.fissionmc.protocol.GhostMeta
import net.fissionmc.protocol.GhostPose
import java.util.UUID

/** Builders for the clientbound packets that show a player of another backend as an entity. Ids are the client-facing ids. */
object GhostFrames {
    private val pSetEntityData by lazy { PlayPacketIds.c("SetEntityData") }
    private val pSetEquipment by lazy { PlayPacketIds.c("SetEquipment") }

    fun spawn(id: Int, uuid: UUID, p: GhostPose): ByteArray =
        Limbo.encode(WrapperPlayServerSpawnEntity(id, uuid, EntityTypes.PLAYER, Location(Vector3d(p.x, p.y, p.z), p.yaw, p.pitch), p.headYaw, 0, null))
    fun position(id: Int, p: GhostPose): ByteArray = Limbo.encode(WrapperPlayServerEntityPositionSync(id, LinearPositionPath(Vector3d(p.x, p.y, p.z)), p.yaw, p.pitch, p.onGround))
    fun entityPosition(id: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float, onGround: Boolean): ByteArray = Limbo.encode(WrapperPlayServerEntityPositionSync(id, LinearPositionPath(Vector3d(x, y, z)), yaw, pitch, onGround))

    /** The owner's add entity payload (from the entity id on) with the client-facing id and the ghost uuid. */
    fun entityAdd(payload: ByteArray, id: Int, uuid: UUID): ByteArray {
        var i = 0
        while (i < payload.size && payload[i].toInt() and 0x80 != 0) i++
        i++
        val b = Unpooled.buffer(payload.size + 10)
        VarInts.write(b, PlayPacketIds.c("AddEntity")); VarInts.write(b, id); b.writeLong(uuid.mostSignificantBits).writeLong(uuid.leastSignificantBits)
        b.writeBytes(payload, i + 16, payload.size - i - 16)
        return ByteArray(b.readableBytes()).also { b.readBytes(it) }
    }
    /** ClientboundSetEntityLinkPacket: [id] is leashed to [holderId] (0 ends the link). Layout verified against vanilla's codec (two ints). */
    fun link(id: Int, holderId: Int): ByteArray {
        val b = Unpooled.buffer(12); VarInts.write(b, PlayPacketIds.c("SetEntityLink")); b.writeInt(id).writeInt(holderId)
        return ByteArray(b.readableBytes()).also { b.readBytes(it) }
    }
    fun head(id: Int, headYaw: Float): ByteArray = Limbo.encode(WrapperPlayServerEntityHeadLook(id, headYaw))
    fun remove(vararg ids: Int): ByteArray = Limbo.encode(WrapperPlayServerDestroyEntities(*ids))

    /** Master-owned team every ghost name belongs to: collision rule NEVER (the vanilla client skips player-vs-player pushing for a pusher whose team says never), nothing else changed. */
    const val TEAM = "fission_ghost"
    fun teamCreate(names: List<String>): ByteArray = Limbo.encode(WrapperPlayServerTeams(TEAM, WrapperPlayServerTeams.TeamMode.CREATE,
        WrapperPlayServerTeams.ScoreBoardTeamInfo(net.kyori.adventure.text.Component.empty(), null, null, WrapperPlayServerTeams.NameTagVisibility.ALWAYS, WrapperPlayServerTeams.CollisionRule.NEVER,
            net.kyori.adventure.text.format.NamedTextColor.WHITE, WrapperPlayServerTeams.OptionData.NONE), names))
    fun teamAdd(name: String): ByteArray = Limbo.encode(WrapperPlayServerTeams(TEAM, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, null as WrapperPlayServerTeams.ScoreBoardTeamInfo?, name))
    fun teamRemove(name: String): ByteArray = Limbo.encode(WrapperPlayServerTeams(TEAM, WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES, null as WrapperPlayServerTeams.ScoreBoardTeamInfo?, name))
    /** The ghost's own, unlisted tab line (the client needs a PlayerInfo for the entity's uuid to draw its skin; the real player's line is untouched). */
    fun tabAdd(ghostUuid: UUID, e: TabEntry): ByteArray {
        val g = TabEntry(ghostUuid, e.name, e.properties).also { it.listed = false; it.gameMode = e.gameMode }
        return Limbo.encode(WrapperPlayServerPlayerInfoUpdate(TabEntry.FULL, listOf(g.toInfo())))
    }
    fun tabRemove(ghostUuid: UUID): ByteArray = Limbo.encode(WrapperPlayServerPlayerInfoRemove(listOf(ghostUuid)))
    fun swing(id: Int, hand: Int): ByteArray = Limbo.encode(WrapperPlayServerSwingAnimation(id, if (hand == 0) com.github.retrooper.packetevents.protocol.player.InteractionHand.MAIN_HAND else com.github.retrooper.packetevents.protocol.player.InteractionHand.OFF_HAND, com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation(com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation.Type.WHACK, 6)))
    /** [payload] = the backend's packet payload after the entity id varint. */
    fun entityData(id: Int, payload: ByteArray): ByteArray = withId(pSetEntityData, id, payload)
    fun equipment(id: Int, payload: ByteArray): ByteArray = withId(pSetEquipment, id, payload)

    private fun withId(packet: Int, id: Int, payload: ByteArray): ByteArray {
        val b = Unpooled.buffer(payload.size + 10); VarInts.write(b, packet); VarInts.write(b, id); b.writeBytes(payload)
        return ByteArray(b.readableBytes()).also { b.readBytes(it) }
    }
}

/** A client session as the ghost hub sees it. Every frame written through [ghostSend] must also be fed to the session's cleanup tracking (tab lines, teams) so a transplant removes it. */
interface GhostViewer {
    val ghostUuid: UUID
    /** The backend the client plays on (null: limbo, switching, wrong phase: nothing is shown and nothing is sent). */
    val ghostNode: String?
    /** Writes a clientbound play frame (the hub holds its lock: enqueue, never call back). */
    fun ghostSend(frame: ByteArray)
    /** Entity bookkeeping of the session ([EntityIdMap.onAdded]/[EntityIdMap.onRemoved]), so a transplant removes ghosts with the other entities. */
    fun ghostAdded(clientId: Int)
    fun ghostRemoved(clientId: Int)
}

/**
 * Shows players of other backends as player entities (design: `.dev/docs/tablist-ghosts-commands.md` section 2). Pure logic: poses and metas come in from the backends,
 * frames go out through [GhostViewer.ghostSend]. A player is only ever a ghost for viewers on another backend, within [range] blocks (+[HYSTERESIS] to disappear), in the same dimension,
 * and only once the master knows the player's tab entry ([tabEntry]).
 *
 * A ghost is NOT the player's own uuid: the entity gets a derived uuid ([ghostUuidOf]) and its own unlisted tab line (same name and skin). The vanilla client keeps entities by uuid and ignores an
 * AddEntity whose uuid it already holds, so a ghost with the real uuid made the backend's native entity of the same player (the player moving onto the viewer's backend, or back) be ignored
 * while the ghost was still there; removing the ghost then left the player invisible. With a distinct uuid both can exist at once and each is removed by its own id.
 * All ghost names are members of one master-owned team with collision rule NEVER, so the client does not push the viewer out of a ghost standing at a border.
 */
class GhostHub(
    @Volatile var range: Double = 128.0,
    private val tabEntry: (player: UUID) -> TabEntry? = { null },
    private val log: (String) -> Unit = {},
    /** `ghosts.entity-expiry-seconds`: the entity ghosts of a backend that sent no batch (heartbeats included) for this long are dropped. The backends send a heartbeat every 2 s from a wall-clock thread, so this only has to cover a stalled process. */
    private val entityExpiryMs: Long = ENTITY_EXPIRY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val GHOST_BASE = 1_500_000_000; const val HYSTERESIS = 16.0
        /** A ghost is not spawned closer than this (horizontal blocks) to the viewer: it would be inside the viewer's camera. It appears as soon as the player has moved off. */
        const val MIN_SPAWN_DISTANCE = 1.5
        fun ghostUuidOf(player: UUID): UUID = UUID.nameUUIDFromBytes("fission-ghost:$player".toByteArray())
        fun entityGhostUuidOf(entity: UUID): UUID = UUID.nameUUIDFromBytes("fission-ghost-entity:$entity".toByteArray())
        const val ENTITY_BASE = 1_600_000_000; const val ENTITY_EXPIRY_MS = 30_000L; const val REAL_WAIT_MS = 8_000L
    }

    private class Spawned(val clientId: Int, val name: String, var x: Double, var y: Double, var z: Double, var yaw: Float, var pitch: Float, var head: Float, var onGround: Boolean, var sameNodeSince: Long = 0L)
    private class Member(val viewer: GhostViewer) {
        var pose: GhostPose? = null; var meta: GhostMeta? = null
        val ghosts = HashMap<UUID, Spawned>()    // players this member sees as entities
        var teamSent = false                     // the collision team exists on the viewer's client
        var nextId = GHOST_BASE
        val entGhosts = HashMap<UUID, SpawnedEntity>()   // entities of other backends this member sees
        var nextEntityId = ENTITY_BASE
    }
    private class SpawnedEntity(val clientId: Int, var x: Double, var y: Double, var z: Double, var yaw: Float, var pitch: Float, var head: Float, var onGround: Boolean, var sameNodeSince: Long = 0L, var linkedTo: Int = 0)
    private class SrcEntity(var node: String, var dim: String, var x: Double, var y: Double, var z: Double, var yaw: Float, var pitch: Float, var head: Float, var onGround: Boolean,
                            var add: ByteArray, var data: ByteArray?, var equip: ByteArray?, var item: Boolean = false, var holder: UUID? = null) {
        /** Where the cached AddEntity payload [add] puts the entity (the pose of the report that carried it): a ghost spawned later must be moved to the current pose. */
        var ax = x; var ay = y; var az = z; var ayaw = yaw; var apitch = pitch; var ahead = head
        fun markAdd() { ax = x; ay = y; az = z; ayaw = yaw; apitch = pitch; ahead = head }
    }
    private val entities = HashMap<UUID, SrcEntity>()
    /** Asks the owner backend [node] to hand the item entity to the viewer player (called with the hub's lock held: enqueue, never call back). */
    @Volatile var pickupAsk: ((node: String, entity: UUID, player: UUID, playerNode: String) -> Unit)? = null
    private val pickupAsked = HashMap<Pair<UUID, UUID>, Long>()
    private val nodeSeen = HashMap<String, Long>()
    val entityGhostCount: Int get() = synchronized(lock) { entities.size }
    fun entityGhostsOf(viewer: UUID): Map<UUID, Int> = synchronized(lock) { members[viewer]?.entGhosts?.mapValues { it.value.clientId } ?: emptyMap() }
    private val lock = Any()
    private val members = LinkedHashMap<UUID, Member>()

    var spawnedFrames = 0L; private set
    fun ghostsOf(viewer: UUID): Map<UUID, Int> = synchronized(lock) { members[viewer]?.ghosts?.mapValues { it.value.clientId } ?: emptyMap() }

    fun register(viewer: GhostViewer) { synchronized(lock) { members[viewer.ghostUuid] = Member(viewer) } }

    /** The player left the network: its ghost disappears everywhere. */
    fun unregister(viewer: GhostViewer) {
        synchronized(lock) {
            val u = viewer.ghostUuid
            if (members[u]?.viewer !== viewer) return
            members.remove(u)
            for (m in members.values) drop(m, u)
        }
    }

    /** The client lost its entities (a Login, a transplant cleanup, the limbo): forget them without sending removals, and show the right ones again once it is placed. */
    fun reset(viewer: GhostViewer) { synchronized(lock) { members[viewer.ghostUuid]?.let { it.ghosts.clear(); it.entGhosts.clear(); it.teamSent = false } } }

    /** The viewer's backend changed or its join stream is in: re-evaluate everything it can see and everybody who can see it. */
    fun placed(viewer: GhostViewer) { synchronized(lock) { members[viewer.ghostUuid]?.let { refresh(it) } } }

    /** The player is out of the world list of its backend but still connected (backend lost): nobody sees it. */
    fun hidden(viewer: GhostViewer) { synchronized(lock) { members[viewer.ghostUuid]?.let { it.pose = null; it.meta = null; for (o in members.values) drop(o, viewer.ghostUuid); it.ghosts.clear(); it.entGhosts.clear(); it.teamSent = false } } }

    /** A viewer's tab line for [player] appeared: spawn the ghost if it is waiting for that. */
    fun tabShown(viewer: UUID, player: UUID) {
        synchronized(lock) { val v = members[viewer] ?: return; val s = members[player] ?: return; evaluate(v, s) }
    }

    fun pose(p: GhostPose) {
        synchronized(lock) {
            val m = members[UUID(p.uuidHigh, p.uuidLow)] ?: return
            m.pose = p
            refresh(m)
        }
    }

    fun meta(g: GhostMeta) {
        synchronized(lock) {
            val m = members[UUID(g.uuidHigh, g.uuidLow)] ?: return
            val first = m.meta == null
            m.meta = g
            if (first) { refresh(m); return }
            for (v in members.values) { val sp = v.ghosts[m.viewer.ghostUuid] ?: continue; sendMeta(v, sp, g) }
        }
    }

    fun swing(s: net.fissionmc.protocol.GhostSwing) {
        synchronized(lock) {
            val m = members[UUID(s.uuidHigh, s.uuidLow)] ?: return
            for (v in members.values) { val sp = v.ghosts[m.viewer.ghostUuid] ?: continue; v.viewer.ghostSend(GhostFrames.swing(sp.clientId, s.hand)) }
        }
    }

    // ---- internals (lock held)
    /** [m] moved or changed backend: it as a source for everybody, everybody as a source for it. */
    private fun refresh(m: Member) {
        for (o in members.values) { if (o === m) continue; evaluate(o, m); evaluate(m, o) }
        for ((u, e) in entities) evaluateEntity(m, u, e)
        relink(m) { m.viewer.ghostSend(it) }
    }

    /** Entities near a border of a backend, as that backend sees them (batches arrive every two ticks). */
    fun entityBatch(b: net.fissionmc.protocol.EntityGhostBatch) {
        synchronized(lock) {
            val now = clock()
            nodeSeen[b.origin] = now
            var i = 0
            while (i + 1 < b.removed.size) { val u = UUID(b.removed[i], b.removed[i + 1]); i += 2; if (entities[u]?.node != b.origin) continue; entities.remove(u); for (v in members.values) dropEntity(v, u) }   // only the current owner removes it: after a handover the old owner's "gone" must not delete the new owner's ghost
            for (g in b.upserts) {
                val u = UUID(g.uuidHigh, g.uuidLow)
                var e = entities[u]
                if (e == null) {
                    if (g.flags and 1 == 0) continue
                    e = SrcEntity(b.origin, b.dimension, g.x, g.y, g.z, g.yaw, g.pitch, g.headYaw, g.onGround, g.add, null, null); entities[u] = e
                }
                if (e.node != b.origin) { if (g.flags and 1 == 0) continue; e.node = b.origin }   // a handover: the node that reports the entity as new is its owner now; the old one's updates are stale
                if (g.flags and 8 != 0) e.item = true
                e.dim = b.dimension; e.x = g.x; e.y = g.y; e.z = g.z; e.yaw = g.yaw; e.pitch = g.pitch; e.head = g.headYaw; e.onGround = g.onGround
                if (g.flags and 1 != 0) { e.add = g.add; e.markAdd() }
                if (g.flags and 2 != 0) e.data = g.data
                if (g.flags and 4 != 0) e.equip = g.equip
                for (v in members.values) {
                    val sp = v.entGhosts[u]
                    if (sp != null) { if (g.flags and 2 != 0 && g.data.isNotEmpty()) v.viewer.ghostSend(GhostFrames.entityData(sp.clientId, g.data)); if (g.flags and 4 != 0 && g.equip.isNotEmpty()) v.viewer.ghostSend(GhostFrames.equipment(sp.clientId, g.equip)) }
                    evaluateEntity(v, u, e)
                }
            }
            for (v in members.values) relink(v) { v.viewer.ghostSend(it) }
            val stale = nodeSeen.filter { now - it.value > entityExpiryMs }.keys
            if (stale.isNotEmpty()) {
                val gone = entities.filter { it.value.node in stale }.keys.toList()
                for (u in gone) { entities.remove(u); for (v in members.values) dropEntity(v, u) }
                nodeSeen.keys.removeAll(stale)
            }
        }
    }

    /** The leashes of the entities [b] shows (a complete snapshot per backend and dimension): a link is shown to a viewer while both its ends are ghosts for it. */
    fun entityLinks(b: net.fissionmc.protocol.EntityGhostLinks) {
        synchronized(lock) {
            nodeSeen[b.origin] = clock()
            val now = HashMap<UUID, UUID>()
            var i = 0
            while (i + 3 < b.links.size) { now[UUID(b.links[i], b.links[i + 1])] = UUID(b.links[i + 2], b.links[i + 3]); i += 4 }
            for ((u, e) in entities) if (e.node == b.origin && e.dim == b.dimension) e.holder = now[u]
            for (v in members.values) relink(v) { v.viewer.ghostSend(it) }
        }
    }

    /** Makes the leash links of [v]'s ghosts what they should be: a ghost is linked to the ghost of its holder while that exists for [v], else it is free. Called after every change. */
    private fun relink(v: Member, emit: (ByteArray) -> Unit) {
        if (v.viewer.ghostNode == null) return
        for ((u, sp) in v.entGhosts) {
            val h = entities[u]?.holder
            val want = if (h == null) 0 else v.entGhosts[h]?.clientId ?: 0
            if (want != sp.linkedTo) { sp.linkedTo = want; emit(GhostFrames.link(sp.clientId, want)) }
        }
    }

    /** The vanilla pickup box: the player's box grown by 1 block sideways and 0.5 vertically touches the item's box. */
    private fun maybePickup(v: Member, uuid: UUID, e: SrcEntity, vp: GhostPose, viewerNode: String) {
        val ask = pickupAsk ?: return
        if (Math.abs(e.x - vp.x) > 1.43 || Math.abs(e.z - vp.z) > 1.43 || e.y + 0.25 < vp.y - 0.5 || e.y > vp.y + 2.3) return
        val key = v.viewer.ghostUuid to uuid; val now = System.currentTimeMillis()
        if (now - (pickupAsked[key] ?: 0L) < 500) return
        pickupAsked[key] = now
        if (pickupAsked.size > 4096) pickupAsked.entries.removeIf { now - it.value > 5000 }
        ask(e.node, uuid, v.viewer.ghostUuid, viewerNode)
    }

    /** The frames that bring a new ghost of [e] to the client under [id]: the cached AddEntity, then the current pose (the cached payload holds the pose of the report that carried it), data and equipment. */
    private fun spawnFramesOf(id: Int, uuid: UUID, e: SrcEntity): List<ByteArray> {
        val out = ArrayList<ByteArray>(5)
        out += GhostFrames.entityAdd(e.add, id, entityGhostUuidOf(uuid))
        if (e.x != e.ax || e.y != e.ay || e.z != e.az || e.yaw != e.ayaw || e.pitch != e.apitch) out += GhostFrames.entityPosition(id, e.x, e.y, e.z, e.yaw, e.pitch, e.onGround)
        if (e.head != e.ahead) out += GhostFrames.head(id, e.head)
        e.data?.takeIf { it.isNotEmpty() }?.let { out += GhostFrames.entityData(id, it) }
        e.equip?.takeIf { it.isNotEmpty() }?.let { out += GhostFrames.equipment(id, it) }
        return out
    }

    /** What a transplant of a viewer keeps on its client and what it adds: [keep] = the client ids of the ghosts that stay (the caller must not remove them), [frames] = written inside the bundle after the removal. */
    class TransplantGhosts(val keep: Set<Int>, val frames: List<ByteArray>)

    /**
     * The viewer's client moves to [node] without a Login (a transplant): the caller removes everything the old backend put on the client EXCEPT [TransplantGhosts.keep], then writes
     * [TransplantGhosts.frames]; all inside one bundle, so nothing a player can see goes away in between. Ghosts stay when the thing they show is still in range and still not
     * provided by the new backend: a ghost of an entity or player OWNED BY [node] stays until the new backend's own copy arrives ([realArrived], [realPlayerArrived], else after [REAL_WAIT_MS]),
     * because the new backend streams its entities with its chunks, seconds after the cut-over. Entities of the other backends that are not ghosts yet (the old backend's own, real until now) are
     * shown as ghosts at once. Player ghosts come back with their tab line and the team (the cleanup removed those: the client's tab list and scoreboard are cleared by every transplant).
     */
    fun transplant(viewer: GhostViewer, node: String): TransplantGhosts = synchronized(lock) {
        val m = members[viewer.ghostUuid] ?: return TransplantGhosts(emptySet(), emptyList())
        val now = clock(); val vp = m.pose
        val keep = HashSet<Int>(); val out = ArrayList<ByteArray>()
        // players
        val keptNames = ArrayList<String>(); val tabs = ArrayList<ByteArray>()
        val itp = m.ghosts.entries.iterator()
        while (itp.hasNext()) {
            val (u, sp) = itp.next(); val s = members[u]; val sPose = s?.pose
            val entry = tabEntry(u)
            val inRange = vp != null && sPose != null && vp.dimension == sPose.dimension && Math.hypot(vp.x - sPose.x, vp.z - sPose.z) <= range + HYSTERESIS
            if (s == null || entry == null || !inRange) { itp.remove(); continue }   // gone: the cleanup removes its entity (its id is not in [keep])
            keep += sp.clientId; keptNames += sp.name
            sp.sameNodeSince = if (s.viewer.ghostNode == node) now else 0L
            tabs += GhostFrames.tabAdd(ghostUuidOf(u), entry)
        }
        out += tabs
        if (keptNames.isNotEmpty()) { out += GhostFrames.teamCreate(keptNames); m.teamSent = true } else m.teamSent = false
        // entities
        val it = m.entGhosts.entries.iterator()
        while (it.hasNext()) {
            val (u, sp) = it.next(); val e = entities[u]
            val inRange = e != null && vp != null && e.dim == vp.dimension && Math.hypot(vp.x - e.x, vp.z - e.z) <= range + HYSTERESIS
            if (e == null || !inRange) { it.remove(); continue }
            keep += sp.clientId
            sp.sameNodeSince = if (e.node == node) now else 0L
        }
        if (vp != null) for ((u, e) in entities) {
            if (e.node == node || e.dim != vp.dimension || u in m.entGhosts) continue
            val dx = vp.x - e.x; val dz = vp.z - e.z
            if (dx * dx + dz * dz > range * range) continue
            val id = m.nextEntityId++
            m.entGhosts[u] = SpawnedEntity(id, e.x, e.y, e.z, e.yaw, e.pitch, e.head, e.onGround); viewer.ghostAdded(id)
            out += spawnFramesOf(id, u, e)
        }
        for ((u, sp) in m.entGhosts) { val h = entities[u]?.holder ?: continue; val hid = m.entGhosts[h]?.clientId ?: continue; sp.linkedTo = hid; out += GhostFrames.link(sp.clientId, hid) }
        TransplantGhosts(keep, out)
    }

    /**
     * The frames that show the entities of the other backends to [viewer] as if it already played on [node], after [reset]: everything is new, nothing is kept (use [transplant] to keep
     * what is still right). The ghosts are registered; the caller writes the frames.
     */
    fun entityFramesFor(viewer: GhostViewer, node: String): List<ByteArray> = synchronized(lock) {
        val m = members[viewer.ghostUuid] ?: return emptyList()
        val vp = m.pose ?: return emptyList()
        m.entGhosts.clear()
        val out = ArrayList<ByteArray>()
        for ((u, e) in entities) {
            if (e.node == node || e.dim != vp.dimension) continue
            val dx = vp.x - e.x; val dz = vp.z - e.z
            if (dx * dx + dz * dz > range * range) continue
            val id = m.nextEntityId++
            m.entGhosts[u] = SpawnedEntity(id, e.x, e.y, e.z, e.yaw, e.pitch, e.head, e.onGround); viewer.ghostAdded(id)
            out += spawnFramesOf(id, u, e)
        }
        for ((u, sp) in m.entGhosts) { val h = entities[u]?.holder ?: continue; val hid = m.entGhosts[h]?.clientId ?: continue; sp.linkedTo = hid; out += GhostFrames.link(sp.clientId, hid) }
        out
    }

    /** The real entity with [uuid] was just sent to [viewer]: the client id of its ghost, which the caller removes, or null. */
    fun realArrived(viewer: GhostViewer, uuid: UUID): Int? = synchronized(lock) { members[viewer.ghostUuid]?.entGhosts?.remove(uuid)?.clientId }

    /** The real player entity of [player] was just sent to [viewer]: its ghost (kept over a transplant) goes, with its tab line and team entry. */
    fun realPlayerArrived(viewer: GhostViewer, player: UUID) { synchronized(lock) { members[viewer.ghostUuid]?.let { drop(it, player) } } }

    private fun dropEntity(v: Member, uuid: UUID) {
        val sp = v.entGhosts.remove(uuid) ?: return
        if (v.viewer.ghostNode != null) v.viewer.ghostSend(GhostFrames.remove(sp.clientId))
        v.viewer.ghostRemoved(sp.clientId)
    }

    private fun evaluateEntity(v: Member, uuid: UUID, e: SrcEntity) {
        val vn = v.viewer.ghostNode; val vp = v.pose
        val existing = v.entGhosts[uuid]
        if (vn == null || vp == null || vp.dimension != e.dim) { if (existing != null) dropEntity(v, uuid); return }
        if (vn == e.node) {
            // the viewer just arrived on the entity's backend: keep the ghost until the real entity shows up ([realArrived]) so it never vanishes in between
            if (existing != null) {
                val now = clock()
                if (existing.sameNodeSince == 0L) existing.sameNodeSince = now
                if (now - existing.sameNodeSince > REAL_WAIT_MS) dropEntity(v, uuid)
            }
            return
        }
        existing?.sameNodeSince = 0L
        val dx = vp.x - e.x; val dz = vp.z - e.z
        val limit = if (existing != null) range + HYSTERESIS else range
        if (dx * dx + dz * dz > limit * limit) { if (existing != null) dropEntity(v, uuid); return }
        if (existing != null && e.item) maybePickup(v, uuid, e, vp, vn)
        if (existing == null) {
            val id = v.nextEntityId++
            v.entGhosts[uuid] = SpawnedEntity(id, e.x, e.y, e.z, e.yaw, e.pitch, e.head, e.onGround); v.viewer.ghostAdded(id)
            spawnFramesOf(id, uuid, e).forEach { v.viewer.ghostSend(it) }; spawnedFrames++
            if (e.item) maybePickup(v, uuid, e, vp, vn)
            return
        }
        if (existing.x != e.x || existing.y != e.y || existing.z != e.z || existing.yaw != e.yaw || existing.pitch != e.pitch || existing.onGround != e.onGround) {
            existing.x = e.x; existing.y = e.y; existing.z = e.z; existing.yaw = e.yaw; existing.pitch = e.pitch; existing.onGround = e.onGround
            v.viewer.ghostSend(GhostFrames.entityPosition(existing.clientId, e.x, e.y, e.z, e.yaw, e.pitch, e.onGround))
        }
        if (existing.head != e.head) { existing.head = e.head; v.viewer.ghostSend(GhostFrames.head(existing.clientId, e.head)) }
    }

    private fun drop(v: Member, player: UUID) {
        val sp = v.ghosts.remove(player) ?: return
        net.fissionmc.net.trace.Trace.emit("ghost.despawn") { str("viewer", v.viewer.ghostUuid.toString()); str("viewerNode", v.viewer.ghostNode); str("ghostOf", player.toString()); str("name", sp.name); int("clientId", sp.clientId) }
        if (v.viewer.ghostNode != null) {
            v.viewer.ghostSend(GhostFrames.remove(sp.clientId))
            v.viewer.ghostSend(GhostFrames.tabRemove(ghostUuidOf(player)))
            if (v.teamSent) v.viewer.ghostSend(GhostFrames.teamRemove(sp.name))
        }
        v.viewer.ghostRemoved(sp.clientId)
    }

    private fun sendMeta(v: Member, sp: Spawned, g: GhostMeta) {
        if (g.entityData.isNotEmpty()) v.viewer.ghostSend(GhostFrames.entityData(sp.clientId, g.entityData))
        if (g.equipment.isNotEmpty()) v.viewer.ghostSend(GhostFrames.equipment(sp.clientId, g.equipment))
    }

    /** Viewer [v] and source [s]: spawn, move or remove the ghost. */
    private fun evaluate(v: Member, s: Member) {
        val vn = v.viewer.ghostNode; val sn = s.viewer.ghostNode
        val vp = v.pose; val sp = s.pose; val meta = s.meta
        val existing = v.ghosts[s.viewer.ghostUuid]
        val ok = vn != null && sn != null && vn != sn && vp != null && sp != null && meta != null && vp.dimension == sp.dimension
        if (!ok) {
            if (existing != null) {
                // the viewer arrived on the player's backend: keep the ghost until the real player entity shows up ([realPlayerArrived]) so the player never vanishes in between
                if (vn != null && vn == sn && vp != null && sp != null && vp.dimension == sp.dimension) {
                    val now = clock()
                    if (existing.sameNodeSince == 0L) existing.sameNodeSince = now
                    if (now - existing.sameNodeSince <= REAL_WAIT_MS) return
                }
                drop(v, s.viewer.ghostUuid)
            }
            return
        }
        existing?.sameNodeSince = 0L
        val dx = vp!!.x - sp!!.x; val dz = vp.z - sp.z; val d2 = dx * dx + dz * dz
        val limit = if (existing != null) range + HYSTERESIS else range
        if (d2 > limit * limit) { if (existing != null) drop(v, s.viewer.ghostUuid); return }
        val uuid = s.viewer.ghostUuid
        if (existing == null) {
            val entry = tabEntry(uuid) ?: return
            if (Math.hypot(dx, dz) < MIN_SPAWN_DISTANCE && Math.abs(vp.y - sp.y) < 2.5) return   // right on top of the viewer: wait until it has moved off
            val id = v.nextId++
            val g = Spawned(id, entry.name, sp.x, sp.y, sp.z, sp.yaw, sp.pitch, sp.headYaw, sp.onGround)
            v.ghosts[uuid] = g; v.viewer.ghostAdded(id)
            net.fissionmc.net.trace.Trace.emit("ghost.spawn") { str("viewer", v.viewer.ghostUuid.toString()); str("viewerNode", vn); str("ghostOf", uuid.toString()); str("name", entry.name); str("sourceNode", sn); int("clientId", id); double("x", sp.x); double("y", sp.y); double("z", sp.z); str("dim", sp.dimension) }
            // order matters: the tab line and the team first, then the entity (the client looks the skin and the team up when the entity appears)
            v.viewer.ghostSend(GhostFrames.tabAdd(ghostUuidOf(uuid), entry))
            if (!v.teamSent) { v.teamSent = true; v.viewer.ghostSend(GhostFrames.teamCreate(listOf(entry.name))) } else v.viewer.ghostSend(GhostFrames.teamAdd(entry.name))
            v.viewer.ghostSend(GhostFrames.spawn(id, ghostUuidOf(uuid), sp)); spawnedFrames++
            sendMeta(v, g, meta!!)
            v.viewer.ghostSend(GhostFrames.head(id, sp.headYaw))
            return
        }
        if (existing.x != sp.x || existing.y != sp.y || existing.z != sp.z || existing.yaw != sp.yaw || existing.pitch != sp.pitch || existing.onGround != sp.onGround) {
            existing.x = sp.x; existing.y = sp.y; existing.z = sp.z; existing.yaw = sp.yaw; existing.pitch = sp.pitch; existing.onGround = sp.onGround
            v.viewer.ghostSend(GhostFrames.position(existing.clientId, sp))
        }
        if (existing.head != sp.headYaw) { existing.head = sp.headYaw; v.viewer.ghostSend(GhostFrames.head(existing.clientId, sp.headYaw)) }
    }
}
