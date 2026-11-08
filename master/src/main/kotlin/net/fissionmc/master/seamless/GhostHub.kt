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
import net.fissionmc.master.client.Limbo
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
    fun head(id: Int, headYaw: Float): ByteArray = Limbo.encode(WrapperPlayServerEntityHeadLook(id, headYaw))
    fun remove(vararg ids: Int): ByteArray = Limbo.encode(WrapperPlayServerDestroyEntities(*ids))
    fun swing(id: Int, hand: Int): ByteArray = Limbo.encode(WrapperPlayServerSwingAnimation(id, if (hand == 0) com.github.retrooper.packetevents.protocol.player.InteractionHand.MAIN_HAND else com.github.retrooper.packetevents.protocol.player.InteractionHand.OFF_HAND, com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation(com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation.Type.WHACK, 6)))
    /** [payload] = the backend's packet payload after the entity id varint. */
    fun entityData(id: Int, payload: ByteArray): ByteArray = withId(pSetEntityData, id, payload)
    fun equipment(id: Int, payload: ByteArray): ByteArray = withId(pSetEquipment, id, payload)

    private fun withId(packet: Int, id: Int, payload: ByteArray): ByteArray {
        val b = Unpooled.buffer(payload.size + 10); VarInts.write(b, packet); VarInts.write(b, id); b.writeBytes(payload)
        return ByteArray(b.readableBytes()).also { b.readBytes(it) }
    }
}

/** A client session as the ghost hub sees it. */
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
 * and only once the viewer's client holds the tab line of the player (vanilla ignores an AddEntity for a player it has no PlayerInfo for) -> [holdsTab].
 */
class GhostHub(
    @Volatile var range: Double = 128.0,
    private val holdsTab: (viewer: UUID, player: UUID) -> Boolean = { _, _ -> true },
    private val log: (String) -> Unit = {},
) {
    companion object { const val GHOST_BASE = 1_500_000_000; const val HYSTERESIS = 16.0 }

    private class Spawned(val clientId: Int, var x: Double, var y: Double, var z: Double, var yaw: Float, var pitch: Float, var head: Float, var onGround: Boolean)
    private class Member(val viewer: GhostViewer) {
        var pose: GhostPose? = null; var meta: GhostMeta? = null
        val ghosts = HashMap<UUID, Spawned>()    // players this member sees as entities
        var nextId = GHOST_BASE
    }
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
    fun reset(viewer: GhostViewer) { synchronized(lock) { members[viewer.ghostUuid]?.ghosts?.clear() } }

    /** The viewer's backend changed or its join stream is in: re-evaluate everything it can see and everybody who can see it. */
    fun placed(viewer: GhostViewer) { synchronized(lock) { members[viewer.ghostUuid]?.let { refresh(it) } } }

    /** The player is out of the world list of its backend but still connected (backend lost): nobody sees it. */
    fun hidden(viewer: GhostViewer) { synchronized(lock) { members[viewer.ghostUuid]?.let { it.pose = null; it.meta = null; for (o in members.values) drop(o, viewer.ghostUuid); it.ghosts.clear() } } }

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
    }

    private fun drop(v: Member, player: UUID) {
        val sp = v.ghosts.remove(player) ?: return
        if (v.viewer.ghostNode != null) v.viewer.ghostSend(GhostFrames.remove(sp.clientId))
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
        if (!ok) { if (existing != null) drop(v, s.viewer.ghostUuid); return }
        val dx = vp!!.x - sp!!.x; val dz = vp.z - sp.z; val d2 = dx * dx + dz * dz
        val limit = if (existing != null) range + HYSTERESIS else range
        if (d2 > limit * limit) { if (existing != null) drop(v, s.viewer.ghostUuid); return }
        val uuid = s.viewer.ghostUuid
        if (existing == null) {
            if (!holdsTab(v.viewer.ghostUuid, uuid)) return
            val id = v.nextId++
            val g = Spawned(id, sp.x, sp.y, sp.z, sp.yaw, sp.pitch, sp.headYaw, sp.onGround)
            v.ghosts[uuid] = g; v.viewer.ghostAdded(id)
            v.viewer.ghostSend(GhostFrames.spawn(id, uuid, sp)); spawnedFrames++
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
