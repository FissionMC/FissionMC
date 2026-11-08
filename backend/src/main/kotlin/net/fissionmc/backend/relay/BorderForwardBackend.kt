package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.backend.replication.Replication
import net.fissionmc.protocol.*
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.ticks.ScheduledTick
import net.minecraft.world.ticks.TickPriority
import java.util.logging.Logger

/**
 * Ownership correct ticking at region borders. A chunk another backend owns is a ghost here: it is never ticked (see ServerLevelRegionMixin), so the work it would produce is sent to
 * its owner instead of being run or dropped on this copy:
 *  - scheduled block and fluid ticks (LevelTicks.schedule of a ghost position: water flow, frosted ice, falling sand, redstone repeaters ...) -> [BorderForward.ticks],
 *  - neighbour updates of a ghost position (BlockStateBase.handleNeighborChanged: a block changed next to the border) -> [BorderForward.neighbors].
 * The owner schedules / runs them on its authoritative copy; what they change replicates back through the normal block replication. Both hooks run on the server thread, the
 * messages are flushed once per tick from [tick]. Received neighbour updates wait [NEIGHBOR_DELAY] ticks so the block change that caused them (replicated separately) has arrived.
 */
object BorderForwardBackend {
    private val log = Logger.getLogger("FissionMC")
    private const val NEIGHBOR_DELAY = 2
    @JvmField val batcher = BorderBatcher({ RegionsBackend.youId() }) { dim, cx, cz -> RegionsBackend.chunkOwner(dim, cx, cz) }
    @JvmField val ticksApplied = java.util.concurrent.atomic.AtomicLong()
    @JvmField val neighborsApplied = java.util.concurrent.atomic.AtomicLong()
    private val inbox = java.util.concurrent.ConcurrentLinkedQueue<BorderForward>()
    private const val HOLD_TICKS = 100L
    private class Due(val dueTick: Long, val deadline: Long, val dimension: String, val n: BorderNeighbor)
    private class DueTick(val deadline: Long, val dimension: String, val t: BorderTick)
    private val pendingNeighbors = ArrayDeque<Due>()
    private val pendingTicks = ArrayDeque<DueTick>()
    @JvmField val dropped = java.util.concurrent.atomic.AtomicLong()
    private var serverTicks = 0L
    private var lastDebug = ""

    /** LevelTicks.schedule hook. True = the tick belongs to another backend and was queued for it (the caller must not schedule it here). */
    @JvmStatic fun forwardTick(level: ServerLevel, tick: ScheduledTick<*>): Boolean {
        if (!RegionsBackend.enabled) return false
        val pos = tick.pos()
        // a replicated change being written into this ghost copy: its owner ran onPlace itself, nothing to forward and nothing to queue on the ghost
        if (net.fissionmc.backend.replication.Replication.isApplying() && batcher.foreignOwner(level.dimension().identifier().toString(), pos.x, pos.z) != null) return true; val type = tick.type()
        val fluid = type is Fluid
        val id = (if (fluid) BuiltInRegistries.FLUID.getKey(type as Fluid) else if (type is Block) BuiltInRegistries.BLOCK.getKey(type) else return false).toString()
        val delay = Math.max(1L, tick.triggerTick() - level.gameTime).coerceAtMost(1_000_000L).toInt()
        return batcher.addTick(level.dimension().identifier().toString(), BorderTick(pos.x, pos.y, pos.z, fluid, id, delay, tick.priority().value))
    }

    /** BlockStateBase.handleNeighborChanged hook. True = the block at [pos] belongs to another backend: the update was queued for its owner and must not run on this ghost. */
    @JvmStatic fun forwardNeighbor(level: Level, pos: BlockPos, changed: Block): Boolean {
        if (!RegionsBackend.enabled || level !is ServerLevel) return false
        return batcher.addNeighbor(level.dimension().identifier().toString(), BorderNeighbor(pos.x, pos.y, pos.z, BuiltInRegistries.BLOCK.getKey(changed).toString()))
    }

    fun onMessage(m: BorderForward) { inbox.add(m) }

    /** Server thread, every tick: send what was queued, apply what arrived. */
    fun tick(server: MinecraftServer) {
        serverTicks++
        val link = NodeBootstrap.link?.takeIf { it.isOpen }
        for (f in batcher.drain()) link?.send(f)
        while (true) {
            val m = inbox.poll() ?: break
            val level = server.allLevels.firstOrNull { it.dimension().identifier().toString() == m.dimension } ?: continue
            // forwarded work is a demand in itself: keep the chunk loaded and ticking, and hold the work until it is (it used to be dropped when the owner had no player near the border)
            val chunks = HashSet<Long>()
            m.ticks.forEach { chunks += net.minecraft.world.level.ChunkPos.pack(it.x shr 4, it.z shr 4) }
            m.neighbors.forEach { chunks += net.minecraft.world.level.ChunkPos.pack(it.x shr 4, it.z shr 4) }
            for (k in chunks) {
                val cx = net.minecraft.world.level.ChunkPos.getX(k); val cz = net.minecraft.world.level.ChunkPos.getZ(k)
                if (RegionsBackend.ownsChunk(level, cx, cz)) level.chunkSource.addTicketWithRadius(net.minecraft.server.level.TicketType.ENDER_PEARL, net.minecraft.world.level.ChunkPos(cx, cz), 2)
            }
            m.ticks.forEach { pendingTicks.addLast(DueTick(serverTicks + HOLD_TICKS, m.dimension, it)) }
            m.neighbors.forEach { pendingNeighbors.addLast(Due(serverTicks + NEIGHBOR_DELAY, serverTicks + HOLD_TICKS, m.dimension, it)) }
            if (net.fissionmc.net.FissionDebug.on) { val l = "[fission] border: from ${m.origin} ${m.ticks.size} ticks ${m.neighbors.size} neighbour updates (total applied ticks=${ticksApplied.get()} neighbours=${neighborsApplied.get()}, forwarded ticks=${batcher.forwardedTicks} neighbours=${batcher.forwardedNeighbors}, dropped=${dropped.get()})"; if (l != lastDebug) { lastDebug = l; log.info(l) } }
        }
        for (i in 0 until pendingTicks.size) {
            val d = pendingTicks.removeFirst()
            val level = server.allLevels.firstOrNull { it.dimension().identifier().toString() == d.dimension } ?: continue
            val pos = BlockPos(d.t.x, d.t.y, d.t.z)
            if (!RegionsBackend.ownsChunk(level, pos.x shr 4, pos.z shr 4)) { dropped.incrementAndGet(); continue }   // not ours (a stale message)
            if (!level.isLoaded(pos)) { if (serverTicks < d.deadline) pendingTicks.addLast(d) else dropped.incrementAndGet(); continue }
            applyTick(level, d.t)
        }
        // a neighbour update waits NEIGHBOR_DELAY ticks and until the block replication has caught up (the block change that caused it travels separately), at most HOLD_TICKS
        val ready = Replication.idle()
        for (i in 0 until pendingNeighbors.size) {
            val d = pendingNeighbors.removeFirst()
            if (d.dueTick > serverTicks || (!ready && serverTicks < d.deadline)) { pendingNeighbors.addLast(d); continue }
            val level = server.allLevels.firstOrNull { it.dimension().identifier().toString() == d.dimension } ?: continue
            val pos = BlockPos(d.n.x, d.n.y, d.n.z)
            if (!RegionsBackend.ownsChunk(level, pos.x shr 4, pos.z shr 4)) { dropped.incrementAndGet(); continue }
            if (!level.isLoaded(pos)) { if (serverTicks < d.deadline) pendingNeighbors.addLast(d) else dropped.incrementAndGet(); continue }
            val id = Identifier.tryParse(d.n.block) ?: continue
            if (!BuiltInRegistries.BLOCK.containsKey(id)) continue
            level.neighborChanged(pos, BuiltInRegistries.BLOCK.getValue(id), null)
            neighborsApplied.incrementAndGet()
        }
    }

    private fun applyTick(level: ServerLevel, t: BorderTick) {
        val pos = BlockPos(t.x, t.y, t.z)
        val id = Identifier.tryParse(t.type) ?: return
        val prio = TickPriority.byValue(t.priority)
        if (t.fluid) {
            if (!BuiltInRegistries.FLUID.containsKey(id)) return
            val f = BuiltInRegistries.FLUID.getValue(id)
            if (!level.fluidTicks.hasScheduledTick(pos, f)) level.scheduleTick(pos, f, t.delay, prio)
        } else {
            if (!BuiltInRegistries.BLOCK.containsKey(id)) return
            val b = BuiltInRegistries.BLOCK.getValue(id)
            if (!level.blockTicks.hasScheduledTick(pos, b)) level.scheduleTick(pos, b, t.delay, prio)
        }
        ticksApplied.incrementAndGet()
    }
}
