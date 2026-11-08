package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
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
    private class Due(val dueTick: Long, val dimension: String, val n: BorderNeighbor)
    private val pendingNeighbors = ArrayDeque<Due>()
    private var serverTicks = 0L
    private var lastDebug = ""

    /** LevelTicks.schedule hook. True = the tick belongs to another backend and was queued for it (the caller must not schedule it here). */
    @JvmStatic fun forwardTick(level: ServerLevel, tick: ScheduledTick<*>): Boolean {
        if (!RegionsBackend.enabled) return false
        val pos = tick.pos(); val type = tick.type()
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
            for (t in m.ticks) applyTick(level, t)
            m.neighbors.forEach { pendingNeighbors.addLast(Due(serverTicks + NEIGHBOR_DELAY, m.dimension, it)) }
            if (net.fissionmc.net.FissionDebug.on) { val l = "[fission] border: from ${m.origin} ${m.ticks.size} ticks ${m.neighbors.size} neighbour updates (total applied ticks=${ticksApplied.get()} neighbours=${neighborsApplied.get()}, forwarded ticks=${batcher.forwardedTicks} neighbours=${batcher.forwardedNeighbors})"; if (l != lastDebug) { lastDebug = l; log.info(l) } }
        }
        while (pendingNeighbors.isNotEmpty() && pendingNeighbors.first().dueTick <= serverTicks) {
            val d = pendingNeighbors.removeFirst()
            val level = server.allLevels.firstOrNull { it.dimension().identifier().toString() == d.dimension } ?: continue
            val pos = BlockPos(d.n.x, d.n.y, d.n.z)
            if (!RegionsBackend.ownsChunk(level, pos.x shr 4, pos.z shr 4) || !level.isLoaded(pos)) continue   // not ours (a stale message) or not loaded
            val id = Identifier.tryParse(d.n.block) ?: continue
            if (!BuiltInRegistries.BLOCK.containsKey(id)) continue
            level.neighborChanged(pos, BuiltInRegistries.BLOCK.getValue(id), null)
            neighborsApplied.incrementAndGet()
        }
    }

    private fun applyTick(level: ServerLevel, t: BorderTick) {
        val pos = BlockPos(t.x, t.y, t.z)
        if (!RegionsBackend.ownsChunk(level, pos.x shr 4, pos.z shr 4) || !level.isLoaded(pos)) return
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
