package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.EntityGhost
import net.fissionmc.protocol.EntityGhostBatch
import net.fissionmc.protocol.EntityGhostLinks
import net.fissionmc.protocol.MAX_ENTITY_GHOSTS
import net.fissionmc.protocol.splitRemoved
import net.fissionmc.protocol.MAX_ENTITY_GHOST_BLOB
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import java.util.UUID
import java.util.logging.Logger

/** Tells the master about entities near a region border, so players of the neighbouring backend can see them. */
object EntityGhostBackend {
    private val log = Logger.getLogger("FissionMC")
    /** How far from foreign territory an entity is still reported: the master's default `ghosts.range` (128 blocks), so the entities a client sees across the border (mobs track up to 128 blocks) are all there when it switches over. */
    private const val RANGE = 128
    /** An entity whose chunk is unloaded is still reported (kept as known, not "gone") for this long: after its player left, the chunk unloads here and only the other backend's ghost demand loads it again. */
    private const val UNLOADED_HOLD_MS = 60_000L
    private class Last(var x: Double, var y: Double, var z: Double, var yaw: Float, var pitch: Float, var head: Float, var ground: Boolean, var data: ByteArray?, var equip: ByteArray?) { var unloadedSince = 0L }
    private val last = HashMap<String, HashMap<UUID, Last>>()
    private val reportPace = net.fissionmc.net.Pacer(100)    // entity positions: 2 ticks at 20 tps, every tick when the server is slower than that
    private val metaPace = net.fissionmc.net.Pacer(500)      // metadata and equipment: 10 ticks at 20 tps
    /** Wall-clock side (scheduler thread, see [LinkScheduler]): the heartbeat towards the master does not depend on the tick rate. */
    private const val HEARTBEAT_MS = 2_000L          // well inside the master's expiry (ghosts.entity-expiry-seconds, default 30 s)
    private const val LINKS_REFRESH_MS = 10_000L
    private val lastSent = java.util.concurrent.ConcurrentHashMap<String, Long>()       // dimension -> nanoTime of the last batch of any kind
    private val lastLinks = java.util.concurrent.ConcurrentHashMap<String, List<Long>>()   // per dimension: the leash snapshot the master has
    private val lastLinksSent = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var schedulerStarted = false

    /** Scheduler thread, every second: an empty batch refreshes the master's last-seen time of this backend for every dimension that has been reported, so a quiet region, a paused or slow backend keeps its ghosts. */
    private fun heartbeat() {
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        if (!RegionsBackend.enabled) return
        val now = System.nanoTime()
        for ((dim, at) in lastSent.entries.toList()) if (now - at >= HEARTBEAT_MS * 1_000_000L) { lastSent[dim] = now; link.send(EntityGhostBatch(RegionsBackend.youId(), dim, emptyList(), emptyList())) }
        // the leash snapshot is repeated while there is any (a restarted master has none)
        for ((dim, links) in lastLinks.entries.toList()) if (links.isNotEmpty() && now - (lastLinksSent[dim] ?: 0L) >= LINKS_REFRESH_MS * 1_000_000L) { lastLinksSent[dim] = now; link.send(EntityGhostLinks(RegionsBackend.youId(), dim, links)) }
    }

    private fun itemBit(e: Entity): Int = if (e is net.minecraft.world.entity.item.ItemEntity) 8 else 0

    /** Per report pass: chunk (x,z) -> is foreign territory within [RANGE] blocks of it. Cleared at the start of every pass, so the 48 samples per chunk are paid once per chunk, not per entity. */
    private val nearCache = HashMap<Long, Boolean>()
    private val SAMPLES = intArrayOf(-RANGE, -RANGE * 3 / 4, -RANGE / 2, -RANGE / 4, 0, RANGE / 4, RANGE / 2, RANGE * 3 / 4, RANGE)   // ring spacing of 32 blocks: a foreign strip narrower than that between two samples is rare

    private fun nearForeign(dim: String, x: Double, z: Double): Boolean {
        val cx = Math.floorDiv(Math.floor(x).toInt(), 16); val cz = Math.floorDiv(Math.floor(z).toInt(), 16)
        return nearCache.computeIfAbsent((cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)) {
            val me = RegionsBackend.youId()
            var found = false
            loop@ for (dx in SAMPLES) for (dz in SAMPLES) {
                if (dx == 0 && dz == 0) continue
                val o = RegionsBackend.chunkOwner(dim, Math.floorDiv(cx * 16 + 8 + dx, 16), Math.floorDiv(cz * 16 + 8 + dz, 16))
                if (o != null && o != me) { found = true; break@loop }
            }
            found
        }
    }

    @Volatile private var resync = false
    /** A (new) master link: the master knows none of the ghosts this backend reported, and only sends nothing for an unchanged one; report everything again as new. Any thread. */
    @JvmStatic fun onReconnect() { resync = true }

    @JvmStatic fun tick(server: MinecraftServer) {
        if (!RegionsBackend.enabled) return
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        if (!schedulerStarted) { schedulerStarted = true; net.fissionmc.backend.node.LinkScheduler.every("entity ghost heartbeat", 500, ::heartbeat) }
        if (resync) { resync = false; last.clear(); lastLinks.clear(); lastSent.clear(); lastLinksSent.clear() }
        if (!reportPace.due()) return
        val metaTick = metaPace.due()
        for (level in server.allLevels) {
            try { report(server, link, level, metaTick) } catch (t: Throwable) { if (net.fissionmc.net.FissionDebug.on) log.warning("[fission] entity ghosts failed: $t") }
        }
    }

    private class LinkState { val leashes = ArrayList<Long>(); var topology = false }

    private fun report(server: MinecraftServer, link: net.fissionmc.net.NodeLink, level: ServerLevel, metaTick: Boolean) {
        val st = LinkState()
        try { reportEntities(server, link, level, metaTick, st) } finally { reportLinks(link, level.dimension().identifier().toString(), st.leashes, st.topology) }   // after the batch: the master matches the links to the ghosts it knows
    }

    private fun reportEntities(server: MinecraftServer, link: net.fissionmc.net.NodeLink, level: ServerLevel, metaTick: Boolean, st: LinkState) {
        val dim = level.dimension().identifier().toString()
        nearCache.clear()
        val known = last.computeIfAbsent(dim) { HashMap() }
        val seen = HashSet<UUID>()
        val ups = ArrayList<EntityGhost>()
        val leashes = st.leashes   // uuid quadruples: entity, holder (only for ghosted entities; the master shows a link when both ends are ghosts)
        for (e in level.allEntities) {
            if (e is ServerPlayer || e.isRemoved || e.isPassenger) continue
            if (!RegionsBackend.ownsEntity(e) || !nearForeign(dim, e.x, e.z)) continue
            if (seen.size >= MAX_ENTITY_GHOSTS * 4) break
            seen += e.uuid
            if (e is net.minecraft.world.entity.Leashable) e.leashHolder?.let { h -> leashes += e.uuid.mostSignificantBits; leashes += e.uuid.leastSignificantBits; leashes += h.uuid.mostSignificantBits; leashes += h.uuid.leastSignificantBits }
            val l = known[e.uuid]
            if (l == null) {
                val add = EntityGhostPackets.add(level, e) ?: continue
                val data = EntityGhostPackets.data(server, e); val equip = EntityGhostPackets.equipment(server, e)
                if (oversize(e, add, data, equip)) continue
                st.topology = true
                known[e.uuid] = Last(e.x, e.y, e.z, e.yRot, e.xRot, e.yHeadRot, e.onGround(), data, equip)
                ups += EntityGhost(e.uuid.mostSignificantBits, e.uuid.leastSignificantBits, e.x, e.y, e.z, e.yRot, e.xRot, e.yHeadRot, e.onGround(), 7 or itemBit(e), add, data, equip)
                continue
            }
            val dx = e.x - l.x; val dy = e.y - l.y; val dz = e.z - l.z
            val moved = dx * dx + dy * dy + dz * dz >= 1.0 / 256 || Math.abs(e.yRot - l.yaw) >= 0.5f || Math.abs(e.xRot - l.pitch) >= 0.5f || Math.abs(e.yHeadRot - l.head) >= 0.5f || e.onGround() != l.ground
            var flags = 0; var data = ByteArray(0); var equip = ByteArray(0)
            if (metaTick) {
                val d = EntityGhostPackets.data(server, e); val q = EntityGhostPackets.equipment(server, e)
                if (oversize(e, ByteArray(0), d, q)) continue
                if (!d.contentEquals(l.data)) { flags = flags or 2; data = d; l.data = d }
                if (!q.contentEquals(l.equip)) { flags = flags or 4; equip = q; l.equip = q }
            }
            if (!moved && flags == 0) continue
            l.x = e.x; l.y = e.y; l.z = e.z; l.yaw = e.yRot; l.pitch = e.xRot; l.head = e.yHeadRot; l.ground = e.onGround()
            ups += EntityGhost(e.uuid.mostSignificantBits, e.uuid.leastSignificantBits, e.x, e.y, e.z, e.yRot, e.xRot, e.yHeadRot, e.onGround(), flags or itemBit(e), ByteArray(0), data, equip)
        }
        val gone = ArrayList<Long>()
        val it = known.entries.iterator()
        val nowMs = System.currentTimeMillis()
        while (it.hasNext()) {
            val en = it.next()
            if (en.key in seen) { en.value.unloadedSince = 0L; continue }
            // not in the world list: gone for real, or only unloaded (the chunk went away with the last player that looked at it, e.g. one that just switched to the other backend; the other backend's
            // ghost demand loads it again within seconds). An unloaded entity is not "gone": its ghost stays where the viewers last saw it.
            val l = en.value
            val unloaded = !level.isLoaded(net.minecraft.core.BlockPos.containing(l.x, l.y, l.z))
            if (unloaded) {
                if (l.unloadedSince == 0L) l.unloadedSince = nowMs
                if (nowMs - l.unloadedSince <= UNLOADED_HOLD_MS) continue
            }
            gone += en.key.mostSignificantBits; gone += en.key.leastSignificantBits; it.remove()
        }
        if (gone.isNotEmpty()) st.topology = true
        if (ups.isEmpty() && gone.isEmpty()) {
            // nothing changed: the heartbeat (see [heartbeat], wall clock, scheduler thread) keeps the master's ghosts of this backend alive. Remember the dimension so it has one.
            lastSent.putIfAbsent(dim, System.nanoTime())
            return
        }
        lastSent[dim] = System.nanoTime()
        val me = RegionsBackend.youId()
        // by count and by size: a batch is one message, and it must stay under what the link carries
        val batches = ArrayList<List<EntityGhost>>(); var cur = ArrayList<EntityGhost>(); var curBytes = 0L
        for (g in ups) { val sz = g.add.size + g.data.size + g.equip.size + 96L; if (cur.size >= MAX_ENTITY_GHOSTS || (curBytes + sz > BATCH_BYTES && cur.isNotEmpty())) { batches += cur; cur = ArrayList(); curBytes = 0 }; cur += g; curBytes += sz }
        if (cur.isNotEmpty()) batches += cur
        val goneParts = splitRemoved(gone)   // the decoder refuses more than MAX_ENTITY_GHOSTS uuids per batch (it would close the link)
        for (i in 0 until maxOf(batches.size, goneParts.size, 1)) link.send(EntityGhostBatch(me, dim, batches.getOrNull(i) ?: emptyList(), goneParts.getOrNull(i) ?: emptyList()))
    }

    /** The leash snapshot goes out when it changed or a ghost was added or removed (the master matches links to its ghosts), and every 10 s while there is any ([heartbeat], a restarted master). Capped like a batch. */
    private fun reportLinks(link: net.fissionmc.net.NodeLink, dim: String, leashes: List<Long>, topology: Boolean) {
        val prev = lastLinks[dim] ?: emptyList()
        val capped = if (leashes.size > MAX_ENTITY_GHOSTS * 4) leashes.subList(0, MAX_ENTITY_GHOSTS * 4) else leashes
        val changed = capped != prev
        if (!changed && !(topology && capped.isNotEmpty())) return   // (the periodic repeat for a restarted master is in [heartbeat])
        lastLinks[dim] = capped.toList(); lastLinksSent[dim] = System.nanoTime()
        link.send(EntityGhostLinks(RegionsBackend.youId(), dim, capped.toList()))
    }

    private const val BATCH_BYTES = 8L shl 20
    private val warned = java.util.HashSet<UUID>()
    @JvmField val oversized = java.util.concurrent.atomic.AtomicLong()

    /** A ghost part over `limits.entity-ghost-blob` is not sent (the entity stays invisible to the other backend's players); logged once per entity. */
    private fun oversize(e: net.minecraft.world.entity.Entity, add: ByteArray, data: ByteArray, equip: ByteArray): Boolean {
        val max = MAX_ENTITY_GHOST_BLOB
        if (add.size <= max && data.size <= max && equip.size <= max) { warned.remove(e.uuid); return false }
        oversized.incrementAndGet()
        if (warned.add(e.uuid)) log.warning("[fission] ${e.type} ${e.uuid} is not shown across the border: ghost data of ${maxOf(add.size, data.size, equip.size)} bytes is over limits.entity-ghost-blob ($max) (raise it on every node)")
        return true
    }
}
