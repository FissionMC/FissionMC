package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.EntityGhost
import net.fissionmc.protocol.EntityGhostBatch
import net.fissionmc.protocol.MAX_ENTITY_GHOSTS
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import java.util.UUID
import java.util.logging.Logger

/** Tells the master about entities near a region border, so players of the neighbouring backend can see them. */
object EntityGhostBackend {
    private val log = Logger.getLogger("FissionMC")
    private const val RANGE = 96
    private class Last(var x: Double, var y: Double, var z: Double, var yaw: Float, var pitch: Float, var head: Float, var ground: Boolean, var data: ByteArray?, var equip: ByteArray?)
    private val last = HashMap<String, HashMap<UUID, Last>>()
    private var ticks = 0

    private fun itemBit(e: Entity): Int = if (e is net.minecraft.world.entity.item.ItemEntity) 8 else 0

    private fun nearForeign(dim: String, x: Double, z: Double): Boolean {
        val me = RegionsBackend.youId()
        for (dx in intArrayOf(-RANGE, 0, RANGE)) for (dz in intArrayOf(-RANGE, 0, RANGE)) {
            if (dx == 0 && dz == 0) continue
            val o = RegionsBackend.chunkOwner(dim, Math.floorDiv(Math.floor(x).toInt() + dx, 16), Math.floorDiv(Math.floor(z).toInt() + dz, 16))
            if (o != null && o != me) return true
        }
        return false
    }

    @JvmStatic fun tick(server: MinecraftServer) {
        if (!RegionsBackend.enabled) return
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        ticks++
        if (ticks % 2 != 0) return
        val metaTick = ticks % 10 == 0
        for (level in server.allLevels) {
            try { report(server, link, level, metaTick) } catch (t: Throwable) { if (net.fissionmc.net.FissionDebug.on) log.warning("[fission] entity ghosts failed: $t") }
        }
    }

    private fun report(server: MinecraftServer, link: net.fissionmc.net.NodeLink, level: ServerLevel, metaTick: Boolean) {
        val dim = level.dimension().identifier().toString()
        val known = last.computeIfAbsent(dim) { HashMap() }
        val seen = HashSet<UUID>()
        val ups = ArrayList<EntityGhost>()
        for (e in level.allEntities) {
            if (e is ServerPlayer || e.isRemoved || e.isPassenger) continue
            if (!RegionsBackend.ownsEntity(e) || !nearForeign(dim, e.x, e.z)) continue
            if (seen.size >= MAX_ENTITY_GHOSTS * 4) break
            seen += e.uuid
            val l = known[e.uuid]
            if (l == null) {
                val add = EntityGhostPackets.add(level, e) ?: continue
                val data = EntityGhostPackets.data(server, e); val equip = EntityGhostPackets.equipment(server, e)
                known[e.uuid] = Last(e.x, e.y, e.z, e.yRot, e.xRot, e.yHeadRot, e.onGround(), data, equip)
                ups += EntityGhost(e.uuid.mostSignificantBits, e.uuid.leastSignificantBits, e.x, e.y, e.z, e.yRot, e.xRot, e.yHeadRot, e.onGround(), 7 or itemBit(e), add, data, equip)
                continue
            }
            val dx = e.x - l.x; val dy = e.y - l.y; val dz = e.z - l.z
            val moved = dx * dx + dy * dy + dz * dz >= 1.0 / 256 || Math.abs(e.yRot - l.yaw) >= 0.5f || Math.abs(e.xRot - l.pitch) >= 0.5f || Math.abs(e.yHeadRot - l.head) >= 0.5f || e.onGround() != l.ground
            var flags = 0; var data = ByteArray(0); var equip = ByteArray(0)
            if (metaTick) {
                val d = EntityGhostPackets.data(server, e); val q = EntityGhostPackets.equipment(server, e)
                if (!d.contentEquals(l.data)) { flags = flags or 2; data = d; l.data = d }
                if (!q.contentEquals(l.equip)) { flags = flags or 4; equip = q; l.equip = q }
            }
            if (!moved && flags == 0) continue
            l.x = e.x; l.y = e.y; l.z = e.z; l.yaw = e.yRot; l.pitch = e.xRot; l.head = e.yHeadRot; l.ground = e.onGround()
            ups += EntityGhost(e.uuid.mostSignificantBits, e.uuid.leastSignificantBits, e.x, e.y, e.z, e.yRot, e.xRot, e.yHeadRot, e.onGround(), flags or itemBit(e), ByteArray(0), data, equip)
        }
        val gone = ArrayList<Long>()
        val it = known.entries.iterator()
        while (it.hasNext()) { val en = it.next(); if (en.key !in seen) { gone += en.key.mostSignificantBits; gone += en.key.leastSignificantBits; it.remove() } }
        if (ups.isEmpty() && gone.isEmpty()) return
        val me = RegionsBackend.youId()
        ups.chunked(MAX_ENTITY_GHOSTS).ifEmpty { listOf(emptyList()) }.forEachIndexed { i, chunk -> link.send(EntityGhostBatch(me, dim, chunk, if (i == 0) gone else emptyList())) }
    }
}
