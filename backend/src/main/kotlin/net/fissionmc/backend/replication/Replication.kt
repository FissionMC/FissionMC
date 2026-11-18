package net.fissionmc.backend.replication

import net.fissionmc.master.traceEvent
import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.backend.relay.RegionsBackend
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

    /** Changes made while the master link is down (ticking goes on: fluids, crops, fire): the latest state per block, sent after the reconnect. Bounded; beyond the bound they are dropped and counted. */
    private val offline = LinkedHashMap<Key, BlockChange>()
    private val offlineRemoved = ArrayList<EntityRemoved>()
    private const val OFFLINE_MAX = 200_000
    @Volatile var sentAfterReconnect = 0L; private set

    /** Local events dropped because the master link was down and the offline queue was full. */
    @Volatile var droppedWhileDisconnected = 0L; private set
    @Volatile var appliedCount = 0L; private set
    @Volatile var refused = 0L; private set

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
        if (!enabled) return
        val dim = level.dimension().identifier().toString()
        if (applying.get()) {
            val target = applyingPos
            if (target == null || pos == target || !RegionsBackend.ownsBlock(dim, pos.x, pos.z)) return
        }
        val x = pos.x; val y = pos.y; val z = pos.z
        // "ghost" = a write into a chunk another backend owns (a player of this backend builds or breaks across the border): the owner may refuse it (see [refusal])
        val cause = if (RegionsBackend.enabled && !RegionsBackend.ownsBlock(dim, x, z)) "ghost" else "block"
        if (!submit(BlockChange(dim, x, y, z, Block.getId(state), cause))) return
        synchronized(pending) { pending.merge(Key(dim, x, y, z), 1, Int::plus) }
    }

    /** No replicated event is waiting to be applied (ghost hashes are only taken then). */
    @JvmStatic fun idle(): Boolean = inbox.isEmpty()

    /** True while a replicated change is being applied on this thread (hooks use it to tell echoes from real gameplay). */
    @JvmStatic fun isApplying(): Boolean = applying.get()
    private var applyingPos: BlockPos? = null

    /** Shared by remote events and ghost fixes: writes [state] without neighbour updates and makes sure watching clients get the block even when the chunk is not block ticking. */
    @JvmStatic fun applyRemote(level: ServerLevel, pos: BlockPos, state: BlockState): BlockState {
        applying.set(true); applyingPos = pos.immutable()
        try { level.setBlock(pos, state, APPLY_FLAGS) } finally { applying.set(false); applyingPos = null }
        // Level.setBlock only calls sendBlockUpdated for chunks that are BLOCK_TICKING; a ghost chunk at the edge of the simulation distance is merely FULL and its viewers would keep the old block
        level.chunkSource.blockChanged(pos)
        return level.getBlockState(pos)
    }

    /** Owner side of a "ghost" write: why the block must not go in (an entity of this backend stands there), or null. */
    @JvmStatic fun refusal(level: ServerLevel, pos: BlockPos, state: BlockState): String? {
        val cur = level.getBlockState(pos)
        if (state.isAir || cur == state) return null
        if (!cur.getCollisionShape(level, pos).isEmpty) return null   // already solid here: a shape update of an existing block (fence, wall, stairs) adds no new obstruction
        if (!level.isUnobstructed(state, pos, net.minecraft.world.phys.shapes.CollisionContext.empty())) return "an entity stands in the way"
        return null
    }

    @JvmStatic fun onEntityDied(entity: Entity, cause: String) {
        if (!enabled || applying.get() || entity is Player) return
        val level = entity.level() as? ServerLevel ?: return
        val u = entity.uuid
        submit(EntityRemoved(level.dimension().identifier().toString(), u.mostSignificantBits, u.leastSignificantBits, cause))
    }

    private fun submit(e: ReplicatedEvent): Boolean {
        val l = NodeBootstrap.link
        if (l == null || !l.isOpen) {
            synchronized(offline) {
                when (e) {
                    is BlockChange -> { val k = Key(e.dimension, e.x, e.y, e.z); if (offline.size < OFFLINE_MAX || offline.containsKey(k)) offline[k] = e else droppedWhileDisconnected++ }
                    is EntityRemoved -> if (offlineRemoved.size < OFFLINE_MAX) offlineRemoved += e else droppedWhileDisconnected++
                }
            }
            return false
        }
        net.fissionmc.net.trace.Trace.emit("repl.submit") { traceEvent(this, e) }
        l.send(EventSubmit(e))
        return true
    }

    // ---- remote events (server thread) ----

    @JvmStatic fun tick(server: MinecraftServer) {
        if (!enabled) return
        val before = lastApplied
        // bounded work per tick: half a tick (at least 25 ms, at most 500 ms: a 1 tps server may use more) so a backlog (a backend that was away, a 1 tps server) does not stretch one tick into seconds
        val deadline = System.nanoTime() + net.fissionmc.net.TickPace.budgetMs(net.fissionmc.backend.node.LinkScheduler.msPerTick()) * 1_000_000L
        var n = 0
        while (true) {
            if (++n % 64 == 0 && System.nanoTime() > deadline) break
            val m = inbox.poll() ?: break
            when (m) {
                is Reconnected -> {
                    synchronized(pending) { pending.clear() } // echoes of submissions lost with the old link may never come
                    m.link.send(ResumeFrom(epoch, lastApplied))
                    // what changed here while the link was down goes out now; it is ordered after everything the master already has, so it wins for those blocks
                    val (blocks, removed) = synchronized(offline) { Pair(offline.values.toList(), offlineRemoved.toList()).also { offline.clear(); offlineRemoved.clear() } }
                    for (b in blocks) { m.link.send(EventSubmit(b)); synchronized(pending) { pending.merge(Key(b.dimension, b.x, b.y, b.z), 1, Int::plus) } }
                    for (r in removed) m.link.send(EventSubmit(r))
                    if (blocks.isNotEmpty() || removed.isNotEmpty()) { sentAfterReconnect += blocks.size + removed.size; log.info("[fission] replication: sent ${blocks.size} block changes and ${removed.size} entity removals that happened while the master link was down") }
                }
                is LogReset -> {
                    if (epoch != 0L) log.warning("[fission] the master started a new event log (its replication.state file is gone, or it is another master): block changes made on other backends while this one was away are not replayed")
                    epoch = m.epoch; lastApplied = 0
                }
                is LogTruncated -> {
                    // the master cannot replay what this backend missed (it was away too long, or the master restarted): go on from the master's current position instead of
                    // asking for the gap again and again. Ghost chunks are repaired by the ghost sync; other loaded chunks keep their blocks until they are loaded again.
                    val missed = m.oldestSeq - 1 - lastApplied
                    synchronized(pending) { pending.clear() }   // echoes of own changes in the skipped range never come: they would hold those blocks against all remote changes
                    if (missed > 0) { log.warning("[fission] replication: the master's event log starts at seq ${m.oldestSeq}; this backend continues from there (block changes other backends made while it was away are not replayed)"); lastApplied = m.oldestSeq - 1 }
                }
                is SequencedEvent -> try { apply(server, m) } catch (t: Throwable) { applyFailures++; log.warning("[fission] replication: applying seq=${m.seq} from ${m.origin} failed, the event is skipped: $t") }   // one bad event must not keep the rest of the tick (and the ack) from running
            }
        }
        if (lastApplied != before) NodeBootstrap.link?.takeIf { it.isOpen }?.send(EventAck(lastApplied))
        // a slow server (1 tps) must not leave a backlog for a whole second: the rest is applied between two ticks, like the relay frames
        if (!inbox.isEmpty() && net.fissionmc.backend.node.LinkScheduler.msPerTick() >= 100 && depth < 8 && followUp.compareAndSet(false, true)) server.execute { followUp.set(false); depth++; try { tick(server) } finally { depth-- } }
    }
    private val followUp = java.util.concurrent.atomic.AtomicBoolean()
    private var depth = 0   // server thread: the follow-up cannot nest without end (a task run while the thread blocks runs at once)

    private var resumeAskedAt = -1L
    private var resumeAskedTime = 0L
    private const val RESUME_RETRY_MS = 5_000L
    @JvmField @Volatile var resumesSent = 0L
    @JvmField @Volatile var resumesSuppressed = 0L
    /** Remote events whose application threw (the event is skipped: lastApplied already moved on, as before). */
    @JvmField @Volatile var applyFailures = 0L

    private fun apply(server: MinecraftServer, m: SequencedEvent) {
        if (m.seq <= lastApplied) return // duplicate
        if (m.seq != lastApplied + 1) { // gap: ask for a resend and drop everything until it arrives
            // one request per position: the events that keep arriving before the replay lands are all "gaps" too, and each would start another replay of up to 100,000 events
            val now = System.currentTimeMillis()
            if (lastApplied != resumeAskedAt || now - resumeAskedTime > RESUME_RETRY_MS) {
                resumeAskedAt = lastApplied; resumeAskedTime = now; resumesSent++
                log.warning("[fission] gap: got seq=${m.seq}, expected ${lastApplied + 1}; requesting resend")
                NodeBootstrap.link?.send(ResumeFrom(epoch, lastApplied))
            } else resumesSuppressed++
            inbox.removeIf { it is SequencedEvent }
            return
        }
        lastApplied = m.seq
        val own = m.origin == nodeId
        net.fissionmc.net.trace.Trace.emit("repl.apply") { long("seq", m.seq); str("origin", m.origin); bool("own", own); traceEvent(this, m.event) }
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
                val pos = BlockPos(e.x, e.y, e.z)
                // a chunk of another backend's territory that is not loaded here is not loaded (or generated) for a remote change: setBlock would do that on the server thread. When it is loaded later it comes
                // from the master's chunk store, which the owner keeps current. Chunks this backend owns are always applied (a neighbour's ghost write into them has to land).
                if (!RegionsBackend.ownsBlock(e.dimension, e.x, e.z) && level.chunkSource.getChunkNow(e.x shr 4, e.z shr 4) == null) { skippedUnloaded++; return }
                // the owner decides about writes into its chunks: a refused placement is answered with the authoritative block ("fix", applied by everybody including the writer, whose client gets the real block back)
                if (e.cause == "ghost" && !own && RegionsBackend.ownsBlock(e.dimension, e.x, e.z)) {
                    val why = refusal(level, pos, state)
                    if (why != null) {
                        val cur = level.getBlockState(pos)
                        refused++
                        net.fissionmc.net.trace.Trace.emit("repl.refused") { long("seq", m.seq); str("origin", m.origin); str("dim", e.dimension); int("x", e.x); int("y", e.y); int("z", e.z); str("state", state.toString()); str("why", why); str("answer", cur.toString()) }
                        log.info("[fission] refused a placement from ${m.origin} at ${e.dimension} ${e.x},${e.y},${e.z} ($state): $why; answering with $cur")
                        submit(BlockChange(e.dimension, e.x, e.y, e.z, Block.getId(cur), "fix"))
                        level.chunkSource.blockChanged(pos)
                        return
                    }
                }
                val now = applyRemote(level, pos, state)
                appliedCount++
                net.fissionmc.net.trace.Trace.emit("repl.applied") { long("seq", m.seq); str("dim", e.dimension); int("x", e.x); int("y", e.y); int("z", e.z); str("wanted", state.toString()); str("readback", now.toString()); bool("match", now == state) }
                if (net.fissionmc.net.FissionDebug.on) log.info("[fission] applied seq=${m.seq} BlockChange(${e.cause}) from ${m.origin} ${e.dimension} ${e.x},${e.y},${e.z} -> $state (readback ${if (now == state) "ok" else "MISMATCH $now"})")
                // the readback differs from what the writer asked for (a plugin or the block itself changed it): the owner's result is the truth for everybody
                if (!own && now != state && RegionsBackend.ownsBlock(e.dimension, e.x, e.z)) submit(BlockChange(e.dimension, e.x, e.y, e.z, Block.getId(now), "fix"))
            }
            is EntityRemoved -> {
                val level = level(server, e.dimension) ?: return
                val entity = level.getEntityInAnyDimension(UUID(e.uuidHigh, e.uuidLow))
                if (entity != null && entity !is Player && !entity.isRemoved) {
                    applying.set(true)
                    net.fissionmc.net.trace.Trace.emit("entity.remove.remote") { long("seq", m.seq); str("origin", m.origin); str("entity", entity.uuid.toString()); str("type", entity.type.descriptionId); str("cause", e.cause) }
                    try { entity.discard() } finally { applying.set(false) }
                    appliedCount++
                }
                if (net.fissionmc.net.FissionDebug.on) log.info("[fission] applied seq=${m.seq} EntityRemoved from ${m.origin} ${e.dimension} ${UUID(e.uuidHigh, e.uuidLow)} (${if (entity != null) "found" else "not loaded here"})")
            }
        }
    }

    /** Remote block changes not applied because their chunk is foreign and not loaded here. */
    @JvmField @Volatile var skippedUnloaded = 0L

    private fun level(server: MinecraftServer, dim: String): ServerLevel? =
        server.allLevels.firstOrNull { it.dimension().identifier().toString() == dim }
}
