package net.fissionmc.master.seamless

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.fissionmc.master.client.VarInts

/** Vanilla 26.3 play packet ids by class name without the `Clientbound`/`Serverbound` prefix and `Packet` suffix (`play-packet-ids.txt`, pinned to PacketEvents' table by a test). */
object PlayPacketIds {
    private fun load(dir: String): Map<String, Int> = PlayPacketIds::class.java.getResourceAsStream("/play-packet-ids.txt")!!.bufferedReader().readLines()
        .filter { it.startsWith("$dir ") }.associate { val p = it.split(' '); p[2].removePrefix(if (dir == "C") "Clientbound" else "Serverbound").removeSuffix("Packet") to p[1].toInt() }
    val clientbound: Map<String, Int> by lazy { load("C") }
    val serverbound: Map<String, Int> by lazy { load("S") }
    fun c(name: String): Int = clientbound[name] ?: error("no clientbound packet $name")
    fun s(name: String): Int = serverbound[name] ?: error("no serverbound packet $name")
}

/**
 * Entity ids of one client session. The client keeps its own player id for the whole session. In IDENTITY mode (the first backend
 * of a session: the client's ids ARE the backend's ids) nothing is translated, the map only records which entity ids the client holds. After the first transplant every
 * backend id is translated: the backend's own player id <-> [clientSelf]; every other entity gets a fresh client-facing id from a counter that never repeats, so an id the
 * client saw once is never reused for another entity.
 */
class EntityIdMap {
    @Volatile var clientSelf = -1; private set
    @Volatile var backendSelf = -1; private set
    @Volatile var identity = true; private set
    private val b2c = HashMap<Int, Int>(); private val c2b = HashMap<Int, Int>()
    private var next = FRESH_BASE
    /** Client-facing ids of the entities the client currently holds (everything it was told to add and not to remove), without the player. */
    private val held = LinkedHashSet<Int>()
    /** Entity type (registry id) of the held entities, for metadata and AddEntity data decisions. */
    private val types = HashMap<Int, Int>()

    /** The first Login of the session gave the client its player id. */
    fun start(selfId: Int) { clientSelf = selfId; backendSelf = selfId; identity = true; b2c.clear(); c2b.clear(); held.clear(); types.clear() }

    /** A transplant: a new backend whose own player id is [newBackendSelf]. Everything of the old generation must have been removed from the client before. */
    fun transplantTo(newBackendSelf: Int) { identity = false; backendSelf = newBackendSelf; b2c.clear(); c2b.clear(); held.clear(); types.clear() }

    fun toClient(b: Int): Int {
        if (identity) return b
        if (b == backendSelf) return clientSelf
        return b2c.getOrPut(b) { var c = next++; if (c == clientSelf) c = next++; c2b[c] = b; c }
    }
    /** The backend id behind a client id, or null for an entity this generation never told the client about. */
    fun toBackend(c: Int): Int? {
        if (c == clientSelf) return backendSelf
        if (identity) return c
        return c2b[c]
    }
    fun release(b: Int) { if (identity) return; b2c.remove(b)?.let { c2b.remove(it) } }

    fun onAdded(clientId: Int, type: Int) { if (clientId != clientSelf) { held += clientId; types[clientId] = type } }
    fun onRemoved(clientId: Int) { held -= clientId; types.remove(clientId) }
    fun typeOf(clientId: Int): Int? = types[clientId]
    fun heldIds(): List<Int> = held.toList()

    companion object { const val FRESH_BASE = 1_000_000 }
}

/** Decodes and re-encodes the metadata of an entity (PacketEvents wrappers, verified against the vanilla codecs by the backend check): only used for the few entity types with an entity-id metadata value. */
interface MetadataCodec {
    /** Maps the entity-id values of [frame] (a SetEntityData packet) with [mapId] (backend id -> client id), or null if it could not be decoded/re-encoded. */
    fun rewrite(frame: ByteArray, entityClientId: Int, entries: List<EntityIdTable.MetaEntry>, mapId: (Int) -> Int): ByteArray?
}

/** The entity-id table (`entity-id-fields.txt`): metadata entries and projectile types. */
object EntityIdTable {
    class MetaEntry(val name: String, val index: Int, val kind: String)
    private val lines: List<String> by lazy { EntityIdTable::class.java.getResourceAsStream("/entity-id-fields.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") } }
    val projectileTypes: Set<Int> by lazy { lines.single { it.startsWith("PROJECTILE_TYPES ") }.substringAfter(' ').split(',').map { it.toInt() }.toSet() }
    /** Entity type id -> metadata entries holding entity ids. */
    val metadata: Map<Int, List<MetaEntry>> by lazy {
        val m = HashMap<Int, MutableList<MetaEntry>>()
        for (l in lines.filter { it.startsWith("META ") }) { val p = l.split(' '); p[5].split(',').forEach { t -> m.getOrPut(t.toInt()) { ArrayList() } += MetaEntry(p[1], p[2].toInt(), p[4]) } }
        m
    }
}

/**
 * Rewrites entity ids of the play packets between the client's id space and the current backend's, byte for byte (the packets and
 * fields come from `entity-id-fields.txt`, the wire layouts from the decompiled 26.3 codecs; the backend check `-PcheckEntityIds` verifies every rewritten packet with
 * vanilla's own decoders). A packet this class has no rule for is passed through unchanged: [unhandled] lists the ids of entity-carrying packets that would need one (none).
 */
class EntityRewriter(val map: EntityIdMap, private val meta: MetadataCodec? = null, private val log: (String) -> Unit = {}) {
    private val c = PlayPacketIds.clientbound
    private val s = PlayPacketIds.serverbound
    private fun id(n: String) = PlayPacketIds.c(n)

    // ---- clientbound: first field is a VarInt entity id, the rest is copied
    private val firstId = listOf("Animate", "BlockDestruction", "EntityPositionSync", "HurtAnimation", "MoveEntityPos", "MoveEntityPosRot", "MoveEntityRot", "MoveMinecartAlongTrack",
        "ProjectilePower", "RotateHead", "SetCamera", "SetEntityMotion", "SetEquipment", "SwingAnimation", "TeleportEntity", "UpdateAttributes", "UpdateMobEffect", "RemoveMobEffect",
        "PlayerCombatKill").map { id(it) }.toSet()
    private val pAddEntity = id("AddEntity"); private val pRemove = id("RemoveEntities"); private val pEntityData = id("SetEntityData"); private val pEntityEvent = id("EntityEvent")
    private val pPassengers = id("SetPassengers"); private val pTake = id("TakeItemEntity"); private val pLink = id("SetEntityLink"); private val pSound = id("SoundEntity")
    private val pLookAt = id("PlayerLookAt"); private val pMount = id("MountScreenOpen"); private val pDamage = id("DamageEvent"); private val pDebugEntity = id("DebugEntityValue")
    private val pLogin = id("Login")

    /** Packet ids whose entity ids this class rewrites (the test checks them against the table). */
    val handledClientbound: Set<Int> = firstId + setOf(pAddEntity, pRemove, pEntityData, pEntityEvent, pPassengers, pTake, pLink, pSound, pLookAt, pMount, pDamage)

    private fun bytes(b: ByteBuf) = ByteArray(b.readableBytes()).also { b.readBytes(it) }
    private fun vi(b: ByteBuf) = VarInts.tryRead(b) ?: throw IllegalArgumentException("truncated varint")

    /** A backend frame heading for the client; null = drop it. */
    fun toClient(frame: ByteArray): ByteArray? {
        val src = Unpooled.wrappedBuffer(frame); val pid = vi(src)
        try {
            when (pid) {
                in firstId -> return head(src, pid, frame.size) { map.toClient(it) }
                pAddEntity -> return addEntity(src, pid, frame.size)
                pRemove -> {
                    val n = vi(src); val dst = Unpooled.buffer(frame.size + 8); VarInts.write(dst, pid); VarInts.write(dst, n)
                    repeat(n) { val b = vi(src); val cid = map.toClient(b); map.onRemoved(cid); map.release(b); VarInts.write(dst, cid) }
                    dst.writeBytes(src); return bytes(dst)
                }
                pEntityData -> return entityData(frame, src, pid)
                pEntityEvent -> { val dst = Unpooled.buffer(frame.size); VarInts.write(dst, pid); dst.writeInt(map.toClient(src.readInt())); dst.writeBytes(src); return bytes(dst) }
                pPassengers -> {
                    val dst = Unpooled.buffer(frame.size + 8); VarInts.write(dst, pid); VarInts.write(dst, map.toClient(vi(src)))
                    val n = vi(src); VarInts.write(dst, n); repeat(n) { VarInts.write(dst, map.toClient(vi(src))) }; dst.writeBytes(src); return bytes(dst)
                }
                pTake -> { val dst = Unpooled.buffer(frame.size + 8); VarInts.write(dst, pid); repeat(2) { VarInts.write(dst, map.toClient(vi(src))) }; dst.writeBytes(src); return bytes(dst) }
                pLink -> { val dst = Unpooled.buffer(frame.size); VarInts.write(dst, pid); dst.writeInt(map.toClient(src.readInt())); val d = src.readInt(); dst.writeInt(if (d == 0) 0 else map.toClient(d)); dst.writeBytes(src); return bytes(dst) }
                pSound -> {
                    val dst = Unpooled.buffer(frame.size + 8); VarInts.write(dst, pid)
                    val holder = vi(src); VarInts.write(dst, holder)
                    if (holder == 0) { val n = vi(src); VarInts.write(dst, n); dst.writeBytes(src.readBytes(n)); val has = src.readBoolean(); dst.writeBoolean(has); if (has) dst.writeFloat(src.readFloat()) }
                    VarInts.write(dst, vi(src))                       // source
                    VarInts.write(dst, map.toClient(vi(src)))         // the entity
                    dst.writeBytes(src); return bytes(dst)
                }
                pLookAt -> {
                    val dst = Unpooled.buffer(frame.size + 8); VarInts.write(dst, pid)
                    VarInts.write(dst, vi(src)); repeat(3) { dst.writeDouble(src.readDouble()) }
                    val at = src.readBoolean(); dst.writeBoolean(at)
                    if (at) VarInts.write(dst, map.toClient(vi(src)))
                    dst.writeBytes(src); return bytes(dst)
                }
                pMount -> { val dst = Unpooled.buffer(frame.size); VarInts.write(dst, pid); VarInts.write(dst, vi(src)); VarInts.write(dst, vi(src)); dst.writeInt(map.toClient(src.readInt())); dst.writeBytes(src); return bytes(dst) }
                pDamage -> {
                    val dst = Unpooled.buffer(frame.size + 8); VarInts.write(dst, pid); VarInts.write(dst, map.toClient(vi(src))); VarInts.write(dst, vi(src))   // victim, damage type holder
                    repeat(2) { val w = vi(src); VarInts.write(dst, if (w == 0) 0 else map.toClient(w - 1) + 1) }                                               // cause, direct (id + 1, 0 = none)
                    dst.writeBytes(src); return bytes(dst)
                }
                pDebugEntity -> return null    // debug subscriptions: never forwarded to a client with a rewritten id space
                else -> return frame
            }
        } catch (t: Throwable) { log("[rewrite] clientbound packet $pid not rewritten: $t"); return if (map.identity) frame else null }
    }

    private inline fun head(src: ByteBuf, pid: Int, size: Int, f: (Int) -> Int): ByteArray {
        val dst = Unpooled.buffer(size + 8); VarInts.write(dst, pid); VarInts.write(dst, f(vi(src))); dst.writeBytes(src); return bytes(dst)
    }

    private fun addEntity(src: ByteBuf, pid: Int, size: Int): ByteArray {
        val dst = Unpooled.buffer(size + 8); VarInts.write(dst, pid)
        val b = vi(src); val cid = map.toClient(b); VarInts.write(dst, cid)
        dst.writeBytes(src.readBytes(16))                          // uuid
        val type = vi(src); VarInts.write(dst, type)
        dst.writeBytes(src.readBytes(24))                          // x y z
        val lowest = src.readUnsignedByte().toInt(); dst.writeByte(lowest)  // LpVec3 movement: 1 byte when zero, else 6 bytes (+ a varint when the scale continues)
        if (lowest != 0) { dst.writeBytes(src.readBytes(5)); if (lowest and 4 == 4) VarInts.write(dst, vi(src)) }
        dst.writeBytes(src.readBytes(3))                           // xRot yRot yHeadRot
        val data = vi(src)
        VarInts.write(dst, if (type in EntityIdTable.projectileTypes && data != 0) map.toClient(data) else data)   // projectiles: the owner's entity id
        dst.writeBytes(src)
        map.onAdded(cid, type)
        return bytes(dst)
    }

    private fun entityData(frame: ByteArray, src: ByteBuf, pid: Int): ByteArray {
        val b = vi(src); val cid = map.toClient(b)
        val dst = Unpooled.buffer(frame.size + 8); VarInts.write(dst, pid); VarInts.write(dst, cid); dst.writeBytes(src)
        val entries = map.typeOf(cid)?.let { EntityIdTable.metadata[it] }
        if (entries == null || map.identity || meta == null) return bytes(dst)
        return meta.rewrite(bytes(dst), cid, entries) { map.toClient(it) } ?: bytes(dst).also { log("[rewrite] metadata of entity $cid (type ${map.typeOf(cid)}) not rewritten: ids inside stay as the backend sent them") }
    }

    // ---- serverbound
    private val sInteract = PlayPacketIds.s("Interact"); private val sAttack = PlayPacketIds.s("Attack"); private val sPlayerCommand = PlayPacketIds.s("PlayerCommand")
    private val sPick = PlayPacketIds.s("PickItemFromEntity"); private val sTagQuery = PlayPacketIds.s("EntityTagQuery"); private val sCmdMinecart = PlayPacketIds.s("SetCommandMinecart")
    private val sSpectate = PlayPacketIds.s("SpectatorAction")
    val handledServerbound: Set<Int> = setOf(sInteract, sAttack, sPlayerCommand, sPick, sTagQuery, sCmdMinecart, sSpectate)

    /** Counts of serverbound packets dropped because they named an entity this backend generation never told the client about. */
    @Volatile var droppedUnknown = 0L; private set

    /** A client frame heading for the current backend; null = drop (names an unknown entity). */
    fun toBackend(frame: ByteArray): ByteArray? {
        if (map.identity) return frame
        val src = Unpooled.wrappedBuffer(frame); val pid = vi(src)
        try {
            val skip = if (pid == sTagQuery) 1 else 0
            when (pid) {
                sInteract, sAttack, sPlayerCommand, sPick, sCmdMinecart, sTagQuery -> {
                    val dst = Unpooled.buffer(frame.size + 8); VarInts.write(dst, pid)
                    if (skip == 1) VarInts.write(dst, vi(src))
                    val b = map.toBackend(vi(src)) ?: run { droppedUnknown++; return null }
                    VarInts.write(dst, b); dst.writeBytes(src); return bytes(dst)
                }
                sSpectate -> {   // Optional<VarInt>: bool present + varint? (OptionalInt codec: VarInt 0 = empty, id + 1 otherwise)
                    val w = vi(src); if (w == 0) return frame
                    val b = map.toBackend(w - 1) ?: run { droppedUnknown++; return null }
                    val dst = Unpooled.buffer(frame.size + 8); VarInts.write(dst, pid); VarInts.write(dst, b + 1); dst.writeBytes(src); return bytes(dst)
                }
                else -> return frame
            }
        } catch (t: Throwable) { log("[rewrite] serverbound packet $pid not rewritten: $t"); return null }
    }
}
