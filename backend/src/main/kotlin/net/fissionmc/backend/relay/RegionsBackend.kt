package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
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
    /** `regions.mob-ai-border` (master config, travels in the RegionMap): mobs do not choose destinations across a border. See [mobMayTarget]. */
    @Volatile @JvmField var mobAiBorder: Boolean = true
    /** Fast path for the AI mixins: regions on and the switch on (a plain volatile read, nothing else, when regions are off). */
    @JvmStatic fun mobAiBorderActive(): Boolean = mobAiBorder && enabled

    fun onMessage(m: NodeMessage, server: MinecraftServer?) {
        when (m) {
            is RegionMap -> { mobAiBorder = m.mobAiBorder; net.fissionmc.net.trace.Trace.emit("region.map") { str("you", m.you); int("rules", m.rules.size) }; val changed = you != m.you || rules != m.rules; you = m.you; rules = m.rules; if (changed) log.info("[fission] regions: I am ${m.you}, ${m.rules.size} rules") }   // the master resends every 5 s (a first send can precede the handlers)
            is GenerateChunk -> if (server != null) server.execute { generate(server, m) } else earlyJobs.add(m)   // before the first tick there is no server to run it on: kept for [tick]
            is EntityHandover -> server?.execute { spawnHandover(server, m) }
            is BorderForward -> BorderForwardBackend.onMessage(m)
            is ItemTransfer -> ItemTransferBackend.onMessage(m, server)
            is ItemPickup -> ItemPickupBackend.onPickup(m, server)
            is ItemGive -> ItemPickupBackend.onGive(m, server)
            is ItemGiveTracked -> ItemPickupBackend.onGiveTracked(m, server)
            is ItemGiveAck -> ItemPickupBackend.onAck(m, server)
            is BlockEntitySignal -> SignalSyncBackend.onMessage(m, server)
            is GhostDemand, is GhostSync, is GhostFix -> GhostSyncBackend.onMessage(m)
        }
    }

    fun youId(): String = you
    private val earlyJobs = java.util.concurrent.ConcurrentLinkedQueue<GenerateChunk>()

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

    // ---- mob AI border (mixins: RandomPosMixin, PathNavigationBorderMixin, MoveControlBorderMixin)
    private val dimNames = ConcurrentHashMap<Any, String>()   // ResourceKeys are interned: identity-hashed, so the dimension string is built once, not per AI query
    private fun dimName(level: ServerLevel): String = dimNames.computeIfAbsent(level.dimension()) { level.dimension().identifier().toString() }

    /**
     * May [mob] pick (x, z) as a movement destination? False only when all hold: regions and `regions.mob-ai-border` are on, the destination is on the other side than the mob (a mob on its own side may not go over;
     * a mob already over, waiting for its handover or just arrived, may not go back), and it is not exempt (leashed or leash-pending follower, passenger or vehicle, being handed over, removed).
     * Everything moves freely when regions are off. Cost: one owner lookup per query (no scanning of mobs), made per goal pick / path / wanted position, never per mob per tick unless it is moving.
     */
    @JvmStatic fun mobMayTarget(mob: Mob, x: Int, z: Int): Boolean {
        if (!mobAiBorder || !enabled) return true
        val level = mob.level() as? ServerLevel ?: return true
        val dim = dimName(level)
        // restricted = the destination lies on the other side than the mob: a mob on its own side may not go over, and a mob that already stands over (waiting for its handover, or just arrived)
        // may not go back to the side it came from
        if (!net.fissionmc.net.MobAiBorderMath.restricted(ownsBlock(dim, x, z), ownsBlock(dim, mob.blockX, mob.blockZ))) return true
        if (mobExempt(mob)) return true
        if (net.fissionmc.net.FissionDebug.on) mobAiBlocked.incrementAndGet()
        return false
    }
    /** Mobs the border rule never restricts: removed, passengers, vehicles, being handed over, leashed followers (they cross with their holder). */
    private fun mobExempt(mob: Mob): Boolean =
        mob.isRemoved || mob.isPassenger || mob.isVehicle || handingOver.containsKey(mob.uuid) || leashHolderOf(mob) != null || leashPending(mob)

    /**
     * [mobMayTarget] for goals that walk to something (navigation): also allowed when a player of this backend stands within 2 blocks of the destination, within 24 blocks of the mob, AND the mob
     * really is after that player ([pursues]: its attack target, its owner, or he holds food it is tempted by), so a mob that chases, follows or is tempted by a player who is across the border
     * (hysteresis margin) still goes to him and is handed over once there. A player merely standing near the destination (the user next to the border) no longer lets panic or wander paths through.
     * The player scan only runs for a refused destination (a bound mob), never for the common allowed case.
     */
    @JvmStatic fun mobMayGoTo(mob: Mob, x: Int, z: Int): Boolean {
        if (mobMayTarget(mob, x, z)) return true
        val level = mob.level() as? ServerLevel ?: return true
        for (p in level.players()) {
            if (p.isSpectator || Math.abs(p.x - x) > 2.0 || Math.abs(p.z - z) > 2.0) continue
            val dx = p.x - mob.x; val dz = p.z - mob.z
            if (dx * dx + dz * dz > 24.0 * 24.0) continue
            if (!pursues(mob, p)) continue
            if (net.fissionmc.net.FissionDebug.on) mobAiBlocked.decrementAndGet()
            return true
        }
        return false
    }

    /** Is [mob] really after [p]: it targets him, he is its owner, or he holds an item this animal likes (tempt, breeding food)? */
    private fun pursues(mob: Mob, p: ServerPlayer): Boolean {
        if (mob.target === p) return true
        if (mob is net.minecraft.world.entity.OwnableEntity && mob.owner === p) return true
        return mob is net.minecraft.world.entity.animal.Animal && (mob.isFood(p.mainHandItem) || mob.isFood(p.offhandItem))
    }

    /** May [mob] walk along [path] (every node, not only the end)? A node it may not go to (see [mobMayGoTo]) makes the whole route refused. */
    @JvmStatic fun mobMayWalk(mob: Mob, path: net.minecraft.world.level.pathfinder.Path): Boolean =
        net.fissionmc.net.MobAiBorderMath.firstRefused(path.nodeCount) { i -> val n = path.getNode(i); mobMayGoTo(mob, n.x, n.z) } < 0

    /**
     * Movement-level guard (cube mobs): the horizontal velocity [mob] is about to move with, with the components removed that would take it onto a block it may not go to (it slides along the
     * border instead of over it). Returns null when nothing changes.
     */
    @JvmStatic fun cubeStep(mob: Mob, dx: Double, dz: Double): DoubleArray? {
        if (!mobAiBorderActive() || (dx == 0.0 && dz == 0.0)) return null
        val r = net.fissionmc.net.MobAiBorderMath.clampStep(mob.x, mob.z, dx, dz) { x, z -> mobMayTarget(mob, x, z) }
        return if (r[0] == dx && r[1] == dz) null else r
    }

    /** The direction check of a cube mob hop: the yaw the mob may hop along (see [net.fissionmc.net.MobAiBorderMath.safeYaw]); null when no direction is allowed. */
    @JvmStatic fun cubeHopYaw(mob: Mob, size: Int, yaw: Float): Float? {
        if (!mobAiBorderActive()) return yaw
        val d = net.fissionmc.net.MobAiBorderMath.hopDistance(size)
        val target = mob.target
        if (target != null) return if (mobMayGoTo(mob, target.blockX, target.blockZ)) yaw else null   // an attacker follows its target, or stands
        return net.fissionmc.net.MobAiBorderMath.safeYaw(mob.x, mob.z, yaw, d) { x, z -> mobMayTarget(mob, x, z) }
    }
    @JvmField val mobAiBlocked = java.util.concurrent.atomic.AtomicLong()   // counted in debug mode only

    /** [positions] with every position that [mobMayTarget] refuses replaced by null (random-position search skips nulls and tries again: 10 attempts). */
    @JvmStatic fun borderFiltered(mob: Mob, positions: java.util.function.Supplier<net.minecraft.core.BlockPos?>): java.util.function.Supplier<net.minecraft.core.BlockPos?> =
        java.util.function.Supplier { positions.get()?.takeIf { mobMayTarget(mob, it.x, it.z) } }

    @JvmField val pistonsBlocked = java.util.concurrent.atomic.AtomicLong()
    @JvmStatic fun ownsBlockAt(level: ServerLevel, pos: net.minecraft.core.BlockPos): Boolean = !enabled || ownsBlock(level.dimension().identifier().toString(), pos.x, pos.z)

    @JvmStatic fun ownsChunk(level: ServerLevel, cx: Int, cz: Int): Boolean = !enabled || ownsBlock(level.dimension().identifier().toString(), cx * 16 + 8, cz * 16 + 8)

    @JvmStatic fun ownsEntity(entity: Entity): Boolean {
        if (!enabled || entity is ServerPlayer) return true
        if (carriesPlayer(entity)) return true
        val level = entity.level() as? ServerLevel ?: return true
        // a leashed mob belongs to whoever owns its holder (a mob, a fence knot): it may stray over the border by the lead's length and keeps ticking next to its holder, and the pair crosses together ([leashGroup])
        val holder = leashHolderOf(entity)
        if (holder != null && holder !is ServerPlayer) return ownsEntityDepth(holder, 1)
        if (holder == null && leashPending(entity)) return true   // the holder is not here (yet): keep ticking, vanilla resolves or drops the lead itself
        return ownsBlock(level.dimension().identifier().toString(), entity.blockX, entity.blockZ)
    }
    private fun ownsEntityDepth(entity: Entity, depth: Int): Boolean {
        val holder = if (depth < 8) leashHolderOf(entity) else null
        if (holder != null && holder !is ServerPlayer) return ownsEntityDepth(holder, depth + 1)
        if (carriesPlayer(entity)) return true
        val level = entity.level() as? ServerLevel ?: return true
        return ownsBlock(level.dimension().identifier().toString(), entity.blockX, entity.blockZ)
    }
    /** The resolved leash holder of [e] (null when it is not leashed, or the leash is still pending after a load). */
    @JvmStatic fun leashHolderOf(e: Entity): Entity? {
        if (e !is Leashable) return null
        e.leashHolder?.let { return it }
        // a lead that is only pending (read from a handover blob, resolved on the first tick) already names its holder: follow it by uuid, or the follower looks unleashed
        val id = e.leashData?.delayedLeashInfo?.left()?.orElse(null) ?: return null
        return (e.level() as? ServerLevel)?.getEntity(id)
    }
    /** A lead that exists but whose holder is not resolved (yet): the entity must not be treated as an unleashed stray. */
    @JvmStatic fun leashPending(e: Entity): Boolean = e is Leashable && e.mayBeLeashed() && e.leashHolder == null

    /**
     * Everything leashed to [holder], and what is leashed to those, in a stable order (not [holder] itself). These travel in the SAME EntityHandover as the holder: a follower left behind keeps a pending
     * leash to a holder that is gone (vanilla keeps a leash pending while the holder is "being saved", then drops the lead after 100 ticks), the pair must never be split by a border.
     */
    @JvmStatic fun leashGroup(holder: Entity): List<Entity> {
        val out = ArrayList<Entity>(); val seen = HashSet<UUID>(); seen += holder.uuid
        val queue = ArrayDeque<Entity>(); queue += holder
        while (queue.isNotEmpty() && out.size <= 64) {
            val h = queue.removeFirst()
            for (f in Leashable.leashableLeashedTo(h)) { if (f !is Entity || f is ServerPlayer || f.isRemoved || !seen.add(f.uuid)) continue; out += f; queue += f }
        }
        return out
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
    /** Entities that just arrived by a handover are not handed back for 5 s (no ping-pong at the border). */
    private val arrivals = net.fissionmc.net.ArrivalCooldown<UUID>(5_000)

    @JvmStatic fun handoverDone(uuids: List<Long>) { var i = 0; while (i + 1 < uuids.size) { handingOver.remove(UUID(uuids[i], uuids[i + 1])); i += 2 } }
    // wall clock (not ticks): a backend at 1 tps would otherwise look for strays only every 20 s and report nothing for minutes
    private val strayPace = net.fissionmc.net.Pacer(1_000)
    private val strayHintPace = net.fissionmc.net.Pacer(200)
    private val cleanPace = net.fissionmc.net.Pacer(30_000)
    private val statsPace = net.fissionmc.net.Pacer(5_000)

    fun tick(server: MinecraftServer) {
        ItemPickupBackend.tick()
        while (true) { val j = earlyJobs.poll() ?: break; generate(server, j) }
        if (!enabled) { BorderForwardBackend.discardInbox(); GhostSyncBackend.discardInbox(); return }
        BorderForwardBackend.tick(server)
        WireHoldBackend.tick(server)
        EntityGhostBackend.tick(server)
        GhostSyncBackend.tick(server)
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
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
        val stray = strayPace.due(); val hinted = strayHint && strayHintPace.due()
        if (stray || hinted) { strayHint = false; handOverStrays(server, link) }
        if (cleanPace.due()) { val online = server.playerList.players.mapTo(HashSet()) { it.uuid }; lastPos.keys.retainAll(online) }
        if (statsPace.due()) {
            val l = "[fission] regions: stats ownedChunkTicks=${ownedChunkTicks.get()} ghostChunkTicksSkipped=${ghostChunkTicksSkipped.get()} ghostEntityTicksSkipped=${ghostEntityTicksSkipped.get()} " +
                "ghostBlockTicksSkipped=${ghostBlockTicksSkipped.get()} ghostSpawnPassesSkipped=${ghostSpawnPassesSkipped.get()} generateJobs=${generatedCount.get()} handedOver=${handedOverCount.get()} handoversReceived=${receivedCount.get()} foreignEntitiesNotLoaded=${foreignEntitiesSkipped.get()} mobAiBlocked=${mobAiBlocked.get()} pistonsBlocked=${pistonsBlocked.get()}"
            if (l != lastStats) { lastStats = l; if (net.fissionmc.net.FissionDebug.on) log.info(l) }
        }
    }

    private fun handOverStrays(server: MinecraftServer, link: net.fissionmc.net.NodeLink) {
        val now = System.currentTimeMillis()
        handingOver.entries.removeIf { now - it.value > 15_000 }
        for (level in server.allLevels) {
            val dim = level.dimension().identifier().toString()
            val byTarget = HashMap<String, MutableList<List<net.fissionmc.protocol.EntityBlob>>>()   // per target: groups (an entity with what is leashed to it) that must not be split over two messages
            for (e in level.allEntities) {
                // (no tickCount test any more: foreign copies from disk are not loaded at all (PersistentEntitySectionManagerRegionMixin), so every live entity in foreign territory is new and must go to its owner)
                if (e is ServerPlayer || e.isRemoved || e.isPassenger || handingOver.containsKey(e.uuid)) continue
                if (e is Projectile && e.getOwner() is ServerPlayer) continue   // a player's own arrow / pearl / trident is resolved by the owner player's backend (pearls teleport the thrower)
                if (e.passengers.isNotEmpty() && carriesPlayer(e)) continue   // a player's vehicle follows the player (RootVehicle / mount hint), it is never handed over under him
                if (e is Leashable && e.mayBeLeashed()) continue   // (pending leads count: a follower is never handed over alone)
                if (arrivals.recent(e.uuid)) continue   // a follower crosses with its holder (leashGroup), and is owned where the holder is ([ownsEntity])
                if (e is net.minecraft.world.entity.decoration.HangingEntity) continue
                val target = ownerOf(dim, e.blockX, e.blockZ) ?: continue
                if (target == you) continue
                val b = TravelBackend.blobFor(EntityBlobKind.HANDOVER, e) ?: continue
                val followers = leashGroup(e).filter { !it.isPassenger && !handingOver.containsKey(it.uuid) }
                val fb = followers.map { TravelBackend.blobFor(EntityBlobKind.HANDOVER, it) }
                if (fb.any { it == null } || followers.size + 1 > MAX_ENTITY_BLOBS) { if (warnedGroups.add(e.uuid)) log.warning("[fission] ${e.type.descriptionId} ${e.uuid} cannot cross the border with the ${followers.size} entities leashed to it (one cannot be saved, or the group is over limits.entity-blobs): it stays where it is"); continue }
                for (f in followers) handingOver[f.uuid] = now
                byTarget.computeIfAbsent(target) { ArrayList() } += listOf(b) + fb.filterNotNull(); handingOver[e.uuid] = now; handedOverCount.addAndGet(1L + followers.size)
                // the copy that stays here until the owner confirms must not be picked up meanwhile (the owner spawns its own: the item would exist twice); the blob above keeps the real delay
                if (e is net.minecraft.world.entity.item.ItemEntity) e.setPickUpDelay(60)
                net.fissionmc.net.trace.Trace.emit("entity.handover.out") { str("entity", e.uuid.toString()); str("type", e.type.descriptionId); str("dim", dim); int("x", e.blockX); int("y", e.blockY); int("z", e.blockZ); str("to", target); int("id", e.id) }
                if (net.fissionmc.net.FissionDebug.on) log.info("[fission] entities: handing ${e.type.descriptionId} ${e.uuid} at ${e.blockX},${e.blockY},${e.blockZ} over to $target with ${followers.size} leashed followers ${followers.map { it.type.descriptionId + " " + it.uuid + " at " + it.blockX + "," + it.blockZ }}")
            }
            val byteCap = net.fissionmc.protocol.Limits.cur().maxMessage.toLong() - 256 * 1024   // a message over limits.max-message is refused by the writer, then retried every 15 s for ever
            for ((target, groups) in byTarget) for (batch in net.fissionmc.net.packGroupsSized(groups, MAX_ENTITY_BLOBS, byteCap, { it.bytes.size }, { g ->
                if (warnedGroups.add(UUID(g[0].uuidHigh, g[0].uuidLow))) log.warning("[fission] a group of ${g.size} entities (${g.sumOf { it.bytes.size }} bytes) is over limits.max-message: it stays where it is (raise limits.max-message on every node)")
            })) link.send(EntityHandover(you, target, dim, batch))
        }
    }

    private val warnedGroups = HashSet<UUID>()

    /** Messages of at most [max] blobs in which no group is split (a group is never over [max]). */
    @JvmStatic fun <T> packGroups(groups: List<List<T>>, max: Int): List<List<T>> {
        val out = ArrayList<List<T>>(); var cur = ArrayList<T>()
        for (g in groups) { if (cur.isNotEmpty() && cur.size + g.size > max) { out += cur; cur = ArrayList() }; cur += g }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun spawnHandover(server: MinecraftServer, m: EntityHandover) {
        val level = server.allLevels.firstOrNull { it.dimension().identifier().toString() == m.dimension } ?: return
        for (b in m.blobs) { val u = UUID(b.uuidHigh, b.uuidLow); handingOver.remove(u); arrivals.arrived(u) }
        val spawned = TravelBackend.spawnAt(level, m.blobs); receivedCount.addAndGet((spawned.size / 2).toLong())
        net.fissionmc.net.trace.Trace.emit("entity.handover.in") { str("from", m.origin); str("dim", m.dimension); int("offered", m.blobs.size); int("spawned", spawned.size / 2) }
        NodeBootstrap.link?.takeIf { it.isOpen }?.let { l -> for (part in splitUuidPairs(spawned)) l.send(EntityHandoverAck(m.origin, part)) }
    }
}
