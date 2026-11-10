package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Leashable
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.vehicle.VehicleEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Backend side of the static regions: this backend ticks and spawns only in chunks it owns (the rest are ghosts: loaded,
 * sent to players, never ticked), generates the chunks the master asks for, reports where its players are, and hands entities that left its region to the new owner.
 * With no map (or an empty one) it owns everything, as before.
 */
object RegionsBackend {
    private val log = Logger.getLogger("FissionMC")
    @Volatile private var you: String = ""
    @Volatile private var rules: List<RegionRule> = emptyList()
    val enabled: Boolean get() = rules.isNotEmpty() && you.isNotEmpty()

    fun onMessage(m: NodeMessage, server: MinecraftServer?) {
        when (m) {
            is RegionMap -> { net.fissionmc.net.trace.Trace.emit("region.map") { str("you", m.you); int("rules", m.rules.size) }; val changed = you != m.you || rules != m.rules; you = m.you; rules = m.rules; if (changed) log.info("[fission] regions: I am ${m.you}, ${m.rules.size} rules") }   // the master resends every 5 s (a first send can precede the handlers)
            is GenerateChunk -> server?.execute { generate(server, m) }
            is EntityHandover -> server?.execute { spawnHandover(server, m) }
            is BorderForward -> BorderForwardBackend.onMessage(m)
            is GhostDemand, is GhostSync, is GhostFix -> GhostSyncBackend.onMessage(m)
        }
    }

    fun youId(): String = you

    /** The backend that owns chunk ([cx],[cz]) (decided by the chunk's centre block, like [ownsChunk]); null when regions are off or nobody is known. */
    fun chunkOwner(dim: String, cx: Int, cz: Int): String? = if (!enabled) null else ownerOf(dim, cx * 16 + 8, cz * 16 + 8)

    private fun ownerOf(dim: String, x: Int, z: Int): String? {
        val rs = rules
        fun pick(d: String) = rs.firstOrNull { it.dimension == d && x >= it.x1 && x < it.x2 && z >= it.z1 && z < it.z2 }?.backend
        return if (rs.any { it.dimension == dim }) pick(dim) else pick("*")   // a dimension with rules uses only them; the rest falls back to regions.default
    }

    // counters (shown in the periodic "[fission] regions:" log line)
    @JvmField val ownedChunkTicks = java.util.concurrent.atomic.AtomicLong()
    @JvmField val ghostChunkTicksSkipped = java.util.concurrent.atomic.AtomicLong()
    @JvmField val ownedEntityTicks = java.util.concurrent.atomic.AtomicLong()
    @JvmField val ghostEntityTicksSkipped = java.util.concurrent.atomic.AtomicLong()
    @JvmField val ghostBlockTicksSkipped = java.util.concurrent.atomic.AtomicLong()
    @JvmField val ghostSpawnPassesSkipped = java.util.concurrent.atomic.AtomicLong()
    private val handedOverCount = java.util.concurrent.atomic.AtomicLong()
    private val receivedCount = java.util.concurrent.atomic.AtomicLong()
    private val generatedCount = java.util.concurrent.atomic.AtomicLong()
    private var lastStats = ""

    /** Does this backend own block (x, z)? (everything when regions are off or the dimension has no rule) */
    @JvmStatic fun ownsBlock(dim: String, x: Int, z: Int): Boolean {
        if (!enabled) return true
        val o = ownerOf(dim, x, z) ?: return true
        return o == you
    }

    @JvmField val pistonsBlocked = java.util.concurrent.atomic.AtomicLong()
    @JvmStatic fun ownsBlockAt(level: ServerLevel, pos: net.minecraft.core.BlockPos): Boolean = !enabled || ownsBlock(level.dimension().identifier().toString(), pos.x, pos.z)

    @JvmStatic fun ownsChunk(level: ServerLevel, cx: Int, cz: Int): Boolean = !enabled || ownsBlock(level.dimension().identifier().toString(), cx * 16 + 8, cz * 16 + 8)

    @JvmStatic fun ownsEntity(entity: Entity): Boolean {
        if (!enabled || entity is ServerPlayer) return true
        if (carriesPlayer(entity)) return true
        val level = entity.level() as? ServerLevel ?: return true
        return ownsBlock(level.dimension().identifier().toString(), entity.blockX, entity.blockZ)
    }

    @JvmField val foreignEntitiesSkipped = java.util.concurrent.atomic.AtomicLong()
    /** An entity read from disk / worldgen: may this backend hold it? (its position is in a chunk this backend owns, or regions are off) */
    @JvmStatic fun ownsEntityAtLoad(entity: Entity): Boolean {
        if (!enabled) return true
        val level = entity.level() as? ServerLevel ?: return true
        val ok = ownsBlock(level.dimension().identifier().toString(), entity.blockX, entity.blockZ)
        if (!ok) net.fissionmc.net.trace.Trace.emit("entity.notloaded") { str("entity", entity.uuid.toString()); str("type", entity.type.descriptionId); int("x", entity.blockX); int("z", entity.blockZ); str("owner", ownerOf(level.dimension().identifier().toString(), entity.blockX, entity.blockZ)) }
        if (!ok && net.fissionmc.net.FissionDebug.on) log.info("[fission] entities: not loading ${entity.type.descriptionId} ${entity.uuid} at ${entity.blockX},${entity.blockY},${entity.blockZ} (${level.dimension().identifier()}): owned by ${ownerOf(level.dimension().identifier().toString(), entity.blockX, entity.blockZ)}")
        return ok
    }

    /**
     * A vehicle (boat, minecart, horse, ...) with a player somewhere in its passenger tree is ticked by the player's backend wherever it is. The player is here, the other side only has a ghost,
     * and a frozen vehicle under a steering player breaks the vanilla movement checks ("moved too quickly", rubber band) during the hysteresis stretch behind the border.
     */
    @JvmStatic fun carriesPlayer(entity: Entity): Boolean {
        if (entity.passengers.isEmpty()) return false
        for (p in entity.indirectPassengers) if (p is ServerPlayer) return true
        return false
    }

    /** Called when a non-owned entity is skipped: a vehicle that was really ticking here has just rolled/drifted over the border, so the next hand-over pass should not wait a second. */
    @JvmStatic fun noteSkipped(entity: Entity) {
        if (entity.tickCount > 0 && entity is VehicleEntity) strayHint = true
    }
    @Volatile private var strayHint = false

    // ---- generation jobs
    private fun generate(server: MinecraftServer, m: GenerateChunk) {
        val level = server.allLevels.firstOrNull { it.dimension().identifier().toString() == m.dimension } ?: return
        generatedCount.incrementAndGet()
        // NON blocking (Bukkit's async chunk load): ServerChunkCache.getChunkFuture blocks the server thread (managedBlock) and two backends waiting for each other deadlocked
        try { level.world.getChunkAtAsync(m.x, m.z, true) } catch (t: Throwable) { log.warning("[fission] generate ${m.dimension} ${m.x},${m.z} failed: $t") }
    }

    // ---- reports and handovers (server thread, from tick)
    private val lastPos = ConcurrentHashMap<UUID, IntArray>()   // dimension hash, x, z, chunk x, chunk z of the last report
    private val handingOver = ConcurrentHashMap<UUID, Long>()
    private var ticks = 0

    fun tick(server: MinecraftServer) {
        if (!enabled) return
        BorderForwardBackend.tick(server)
        GhostSyncBackend.tick(server)
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        ticks++
        for (p in server.playerList.players) {
            if (!PlayerDataBackend.isRelayed(p.uuid)) continue
            // report when the chunk changes or the player moved 2+ blocks: the master's hysteresis (regions.margin, default 8) needs positions finer than chunks
            val last = lastPos[p.uuid]
            if (last != null && last[0] == p.level().dimension().identifier().toString().hashCode() && Math.abs(last[1] - p.blockX) < 2 && Math.abs(last[2] - p.blockZ) < 2 && last[3] == (p.blockX shr 4) && last[4] == (p.blockZ shr 4)) continue
            lastPos[p.uuid] = intArrayOf(p.level().dimension().identifier().toString().hashCode(), p.blockX, p.blockZ, p.blockX shr 4, p.blockZ shr 4)
            run {
                link.send(PlayerPosition(p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, p.level().dimension().identifier().toString(), p.blockX, p.blockZ))
            }
        }
        if (ticks % 20 == 0 || (strayHint && ticks % 4 == 0)) { strayHint = false; handOverStrays(server, link) }
        if (ticks % 100 == 0) {
            val l = "[fission] regions: stats ownedChunkTicks=${ownedChunkTicks.get()} ghostChunkTicksSkipped=${ghostChunkTicksSkipped.get()} ghostEntityTicksSkipped=${ghostEntityTicksSkipped.get()} " +
                "ghostBlockTicksSkipped=${ghostBlockTicksSkipped.get()} ghostSpawnPassesSkipped=${ghostSpawnPassesSkipped.get()} generateJobs=${generatedCount.get()} handedOver=${handedOverCount.get()} handoversReceived=${receivedCount.get()} foreignEntitiesNotLoaded=${foreignEntitiesSkipped.get()} pistonsBlocked=${pistonsBlocked.get()}"
            if (l != lastStats) { lastStats = l; if (net.fissionmc.net.FissionDebug.on) log.info(l) }
        }
    }

    private fun handOverStrays(server: MinecraftServer, link: net.fissionmc.net.NodeLink) {
        val now = System.currentTimeMillis()
        handingOver.entries.removeIf { now - it.value > 15_000 }
        for (level in server.allLevels) {
            val dim = level.dimension().identifier().toString()
            val byTarget = HashMap<String, MutableList<net.fissionmc.protocol.EntityBlob>>()
            for (e in level.allEntities) {
                // (no tickCount test any more: foreign copies from disk are not loaded at all (PersistentEntitySectionManagerRegionMixin), so every live entity in foreign territory is new and must go to its owner)
                if (e is ServerPlayer || e.isRemoved || e.isPassenger || handingOver.containsKey(e.uuid)) continue
                if (e is Projectile && e.getOwner() is ServerPlayer) continue   // a player's own arrow / pearl / trident is resolved by the owner player's backend (pearls teleport the thrower)
                if (e.passengers.isNotEmpty() && carriesPlayer(e)) continue   // a player's vehicle follows the player (RootVehicle / mount hint), it is never handed over under him
                if (e is Leashable && e.isLeashed) continue
                if (e.type.category == net.minecraft.world.entity.MobCategory.MISC && e !is net.minecraft.world.entity.item.ItemEntity && e !is net.minecraft.world.entity.ExperienceOrb && e !is VehicleEntity
                    && e !is net.minecraft.world.entity.item.PrimedTnt && e !is net.minecraft.world.entity.item.FallingBlockEntity && e !is Projectile) continue   // empty boats and minecarts drift over borders; lit TNT and falling blocks fly over them
                val target = ownerOf(dim, e.blockX, e.blockZ) ?: continue
                if (target == you) continue
                val b = TravelBackend.blobFor(EntityBlobKind.HANDOVER, e) ?: continue
                byTarget.computeIfAbsent(target) { ArrayList() } += b; handingOver[e.uuid] = now; handedOverCount.incrementAndGet()
                net.fissionmc.net.trace.Trace.emit("entity.handover.out") { str("entity", e.uuid.toString()); str("type", e.type.descriptionId); str("dim", dim); int("x", e.blockX); int("y", e.blockY); int("z", e.blockZ); str("to", target); int("id", e.id) }
                if (net.fissionmc.net.FissionDebug.on) log.info("[fission] entities: handing ${e.type.descriptionId} ${e.uuid} at ${e.blockX},${e.blockY},${e.blockZ} over to $target")
            }
            for ((target, blobs) in byTarget) blobs.chunked(MAX_ENTITY_BLOBS).forEach { link.send(EntityHandover(you, target, dim, it)) }
        }
    }

    private fun spawnHandover(server: MinecraftServer, m: EntityHandover) {
        val level = server.allLevels.firstOrNull { it.dimension().identifier().toString() == m.dimension } ?: return
        val spawned = TravelBackend.spawnAt(level, m.blobs); receivedCount.addAndGet((spawned.size / 2).toLong())
        net.fissionmc.net.trace.Trace.emit("entity.handover.in") { str("from", m.origin); str("dim", m.dimension); int("offered", m.blobs.size); int("spawned", spawned.size / 2) }
        NodeBootstrap.link?.takeIf { it.isOpen }?.send(EntityHandoverAck(m.origin, spawned))
    }
}
