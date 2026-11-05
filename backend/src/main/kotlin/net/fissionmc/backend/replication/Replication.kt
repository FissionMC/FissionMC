package net.fissionmc.backend.replication

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.net.NodeLink
import net.fissionmc.protocol.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Logger

/**
 * Backend side of replication (D3, conservative set: block changes and entity removals).
 *
 *  - Local changes (mixins call [onSetBlock] / [onEntityDied]) are sent to the master as [EventSubmit].
 *  - The master echoes every event back in its global order as [SequencedEvent] (also to the origin).
 *  - Events are queued from the network thread and applied on the server thread in [tick], strictly in seq order.
 *  - While applying a remote event [applying] is set, so the mixins do not report it again (no echo loops).
 *  - Conflict rule: master order wins. If this node has submitted a change for a block that has not been echoed yet,
 *    remote events for that block are skipped: our own event comes later in the master's order and overwrites them.
 *
 * Disabled (all hooks are a single volatile read) unless node networking is active.
 */
object Replication {
    private val log = Logger.getLogger("FissionMC")
    // UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS: show it to players, but no neighbour updates, no drops.
    private const val APPLY_FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE or Block.UPDATE_SUPPRESS_DROPS

    @Volatile private var enabled = false
    private var nodeId = ""
    private val applying = ThreadLocal.withInitial { false }
    private val inbox = ConcurrentLinkedQueue<NodeMessage>()

    // Server-thread state (only touched from tick()):
    private var epoch = 0L
    @Volatile var lastApplied = 0L; private set

    private data class Key(val dim: String, val x: Int, val y: Int, val z: Int)
    private val pending = HashMap<Key, Int>()

    /** Local events dropped because the master link was down. */
    @Volatile var droppedWhileDisconnected = 0L; private set
    @Volatile var appliedCount = 0L; private set

    @JvmStatic fun attach(id: String) {
        nodeId = id
        NodeBootstrap.addMessageHandler { m -> if (m is SequencedEvent || m is LogReset || m is LogTruncated) inbox.add(m) }
        NodeBootstrap.addConnectListener { l -> inbox.add(Reconnected(l)) }
        enabled = true
        log.info("[fission] replication enabled for node $id")
    }

    private class Reconnected(val link: NodeLink) : NodeMessage

    // ---- local changes (called by mixins on the server thread) ----

    @JvmStatic fun onSetBlock(level: Level, pos: BlockPos, state: BlockState) {
        if (!enabled || applying.get()) return
        val dim = level.dimension().identifier().toString()
        val x = pos.x; val y = pos.y; val z = pos.z
        if (!submit(BlockChange(dim, x, y, z, Block.getId(state), "block"))) return
        synchronized(pending) { pending.merge(Key(dim, x, y, z), 1, Int::plus) }
    }

    @JvmStatic fun onEntityDied(entity: Entity, cause: String) {
        if (!enabled || applying.get() || entity is Player) return
        val level = entity.level() as? ServerLevel ?: return
        val u = entity.uuid
        submit(EntityRemoved(level.dimension().identifier().toString(), u.mostSignificantBits, u.leastSignificantBits, cause))
    }

    private fun submit(e: ReplicatedEvent): Boolean {
        val l = NodeBootstrap.link
        if (l == null || !l.isOpen) { droppedWhileDisconnected++; return false }
        l.send(EventSubmit(e))
        return true
    }

    // ---- remote events (server thread) ----

    @JvmStatic fun tick(server: MinecraftServer) {
        if (!enabled) return
        val before = lastApplied
        while (true) {
            val m = inbox.poll() ?: break
            when (m) {
                is Reconnected -> {
                    synchronized(pending) { pending.clear() } // echoes of submissions lost with the old link may never come
                    m.link.send(ResumeFrom(epoch, lastApplied))
                }
                is LogReset -> {
                    if (epoch != 0L) log.warning("[fission] master restarted (new log epoch): this world may have diverged from the master; restart this backend to resync")
                    epoch = m.epoch; lastApplied = 0
                }
                is LogTruncated -> log.warning("[fission] master log no longer holds history before seq ${m.oldestSeq}; replication is incomplete")
                is SequencedEvent -> apply(server, m)
            }
        }
        if (lastApplied != before) NodeBootstrap.link?.takeIf { it.isOpen }?.send(EventAck(lastApplied))
    }

    private fun apply(server: MinecraftServer, m: SequencedEvent) {
        if (m.seq <= lastApplied) return // duplicate
        if (m.seq != lastApplied + 1) { // gap: ask for a resend and drop everything until it arrives
            log.warning("[fission] gap: got seq=${m.seq}, expected ${lastApplied + 1}; requesting resend")
            NodeBootstrap.link?.send(ResumeFrom(epoch, lastApplied))
            inbox.removeIf { it is SequencedEvent }
            return
        }
        lastApplied = m.seq
        val own = m.origin == nodeId
        when (val e = m.event) {
            is BlockChange -> {
                val k = Key(e.dimension, e.x, e.y, e.z)
                val stillPending = synchronized(pending) {
                    val n = pending[k] ?: 0
                    if (own && n > 0) { if (n == 1) pending.remove(k) else pending[k] = n - 1 }
                    n
                }
                if (own && stillPending > 0) return // echo of our live change, already in the world
                if (stillPending > 0) { log.info("[fission] seq=${m.seq} BlockChange ${k.dim} ${e.x},${e.y},${e.z} skipped: a newer local change is ordered later"); return }
                // (an own event without a pending entry is history from before a restart of this backend: apply it)
                val level = level(server, e.dimension) ?: return log.warning("[fission] seq=${m.seq}: unknown dimension ${e.dimension}")
                val state = Block.stateById(e.stateId)
                applying.set(true)
                try { level.setBlock(BlockPos(e.x, e.y, e.z), state, APPLY_FLAGS) } finally { applying.set(false) }
                appliedCount++
                val now = level.getBlockState(BlockPos(e.x, e.y, e.z))
                log.info("[fission] applied seq=${m.seq} BlockChange from ${m.origin} ${e.dimension} ${e.x},${e.y},${e.z} -> $state (readback ${if (now == state) "ok" else "MISMATCH $now"})")
            }
            is EntityRemoved -> {
                val level = level(server, e.dimension) ?: return
                val entity = level.getEntityInAnyDimension(UUID(e.uuidHigh, e.uuidLow))
                if (entity != null && entity !is Player && !entity.isRemoved) {
                    applying.set(true)
                    try { entity.discard() } finally { applying.set(false) }
                    appliedCount++
                }
                log.info("[fission] applied seq=${m.seq} EntityRemoved from ${m.origin} ${e.dimension} ${UUID(e.uuidHigh, e.uuidLow)} (${if (entity != null) "found" else "not loaded here"})")
            }
        }
    }

    private fun level(server: MinecraftServer, dim: String): ServerLevel? =
        server.allLevels.firstOrNull { it.dimension().identifier().toString() == dim }
}
