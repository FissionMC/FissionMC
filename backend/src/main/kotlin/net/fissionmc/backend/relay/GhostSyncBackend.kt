package net.fissionmc.backend.relay

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.backend.replication.Replication
import net.fissionmc.protocol.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.chunk.LevelChunk
import java.util.logging.Logger

/**
 * Ghost chunks, both ends (see .dev/docs/design/regions-and-traveling-entities.md, "Ghost blocks"):
 *  - VIEWER: every second it tells each owner which of the owner's chunks its players look at ([GhostDemand]); about every [SYNC_INTERVAL_TICKS] ticks (and at once for a newly loaded or reloaded
 *    ghost chunk) it sends the owner a hash per section of its copy ([GhostSync]); sections the owner reports different are overwritten ([GhostFix]).
 *  - OWNER: keeps demanded chunks loaded and ticking (a ticket that expires 2 s after the last demand), compares hashes with its live chunk and answers with the differing sections.
 * The owner's world is the truth, so the repair always flows owner -> ghost. The ghost copy goes stale when the master hands out an older stored version of a chunk (reload after the
 * owner's push), when an event arrives for a chunk that is not loaded, or when a change never produced an event; the periodic check heals all of them.
 */
object GhostSyncBackend {
    private val log = Logger.getLogger("FissionMC")
    const val SYNC_INTERVAL_TICKS = 300L           // 15 s per chunk
    private const val DEMAND_EVERY = 20             // ticks
    private const val SCAN_EVERY = 10
    private const val BUDGET = 6                    // syncs per scan
    private const val MAX_FIX_BLOCKS = 40_000

    private val scheduler = GhostSyncScheduler(SYNC_INTERVAL_TICKS, BUDGET)
    private var ticks = 0L
    @JvmField val syncsSent = java.util.concurrent.atomic.AtomicLong()
    @JvmField val syncsMismatched = java.util.concurrent.atomic.AtomicLong()
    @JvmField val blocksFixed = java.util.concurrent.atomic.AtomicLong()
    @JvmField val demandTicketsHeld = java.util.concurrent.atomic.AtomicLong()
    private val inbox = java.util.concurrent.ConcurrentLinkedQueue<NodeMessage>()

    fun onMessage(m: NodeMessage) { inbox.add(m) }

    /** Block state ids of one section in y, z, x order (the order [SectionCodec] uses). */
    fun stateIds(chunk: LevelChunk, index: Int): IntArray {
        val section = chunk.getSection(index); val out = IntArray(SectionCodec.SIZE)
        if (section.hasOnlyAir()) return out
        var i = 0
        for (y in 0 until 16) for (z in 0 until 16) for (x in 0 until 16) out[i++] = Block.getId(section.getBlockState(x, y, z))
        return out
    }

    fun hashes(chunk: LevelChunk): IntArray = IntArray(chunk.sectionsCount) { SectionCodec.hash(stateIds(chunk, it)) }

    fun tick(server: MinecraftServer) {
        ticks++
        while (true) {
            val m = inbox.poll() ?: break
            when (m) { is GhostDemand -> onDemand(server, m); is GhostSync -> onSync(server, m); is GhostFix -> onFix(server, m) }
        }
        if (!RegionsBackend.enabled) return
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        if (ticks % SCAN_EVERY == 0L) scan(server, link)
        if (ticks % DEMAND_EVERY == 0L) demand(server, link)
    }

    /** Ghost chunks (not owned here, loaded) inside [radius] chunks of a player, per level. */
    private fun ghostChunks(server: MinecraftServer, radius: Int): List<Triple<ServerLevel, String, List<Long>>> {
        val out = ArrayList<Triple<ServerLevel, String, List<Long>>>()
        for (level in server.allLevels) {
            if (level.players().isEmpty()) continue
            val set = LinkedHashSet<Long>()
            for (p in level.players()) {
                val pcx = p.blockX shr 4; val pcz = p.blockZ shr 4
                for (cx in pcx - radius..pcx + radius) for (cz in pcz - radius..pcz + radius) if (!RegionsBackend.ownsChunk(level, cx, cz)) set += ChunkPos.pack(cx, cz)
            }
            if (set.isNotEmpty()) out += Triple(level, level.dimension().identifier().toString(), set.toList())
        }
        return out
    }

    private fun scan(server: MinecraftServer, link: net.fissionmc.net.NodeLink) {
        if (!Replication.idle()) return   // events are still arriving: a hash taken now would only report their effect as a difference
        val radius = server.playerList.viewDistance.coerceAtMost(12)
        for ((level, dim, packed) in ghostChunks(server, radius)) {
            val loaded = HashMap<Long, LevelChunk>()
            for (k in packed) { val c = level.chunkSource.getChunkNow(ChunkPos.getX(k), ChunkPos.getZ(k)) ?: continue; loaded[k] = c }
            val cand = loaded.map { (k, c) -> GhostSyncScheduler.Candidate(k, System.identityHashCode(c)) }
            for (c in scheduler.pick(ticks, cand)) {
                val chunk = loaded[c.key] ?: continue
                val cx = ChunkPos.getX(c.key); val cz = ChunkPos.getZ(c.key)
                val owner = RegionsBackend.chunkOwner(dim, cx, cz) ?: continue
                net.fissionmc.net.trace.Trace.emit("ghost.sync.out") { str("to", owner); str("dim", dim); int("cx", cx); int("cz", cz) }
                link.send(GhostSync(RegionsBackend.youId(), owner, dim, cx, cz, hashes(chunk))); syncsSent.incrementAndGet()
            }
        }
    }

    private fun demand(server: MinecraftServer, link: net.fissionmc.net.NodeLink) {
        val radius = server.playerList.simulationDistance.coerceAtMost(10)
        for ((_, dim, packed) in ghostChunks(server, radius)) {
            val byOwner = HashMap<String, MutableList<Long>>()
            for (k in packed) { val o = RegionsBackend.chunkOwner(dim, ChunkPos.getX(k), ChunkPos.getZ(k)) ?: continue; byOwner.computeIfAbsent(o) { ArrayList() } += k }
            for ((o, list) in byOwner) list.chunked(MAX_GHOST_CHUNKS).forEach { link.send(GhostDemand(RegionsBackend.youId(), o, dim, it.toLongArray())) }
        }
    }

    // ---- owner
    private fun onDemand(server: MinecraftServer, m: GhostDemand) {
        val level = server.allLevels.firstOrNull { it.dimension().identifier().toString() == m.dimension } ?: return
        var n = 0
        for (k in m.chunks) {
            val cx = ChunkPos.getX(k); val cz = ChunkPos.getZ(k)
            if (!RegionsBackend.ownsChunk(level, cx, cz)) continue
            level.chunkSource.addTicketWithRadius(TicketType.ENDER_PEARL, ChunkPos(cx, cz), 2)   // 40 ticks, loads and simulates; refreshed by the next demand
            n++
        }
        demandTicketsHeld.addAndGet(n.toLong())
        net.fissionmc.net.trace.Trace.emit("ghost.demand.in") { str("from", m.origin); str("dim", m.dimension); int("chunks", m.chunks.size); int("owned", n) }
        if (net.fissionmc.net.FissionDebug.on && n > 0) log.info("[fission] ghost demand from ${m.origin}: $n chunks of ${m.dimension} kept ticking")
    }

    private fun onSync(server: MinecraftServer, m: GhostSync) {
        val level = server.allLevels.firstOrNull { it.dimension().identifier().toString() == m.dimension } ?: return
        if (!RegionsBackend.ownsChunk(level, m.cx, m.cz)) return
        val chunk = level.chunkSource.getChunkNow(m.cx, m.cz) ?: return   // not loaded here: nothing newer than the master's store to offer
        if (m.hashes.size != chunk.sectionsCount) return
        val bad = ArrayList<GhostSection>()
        for (i in m.hashes.indices) { val ids = stateIds(chunk, i); if (SectionCodec.hash(ids) != m.hashes[i]) bad += GhostSection(i, SectionCodec.encode(ids)) }
        if (bad.isEmpty()) { if (net.fissionmc.net.FissionDebug.on) log.fine("[fission] ghost sync ${m.dimension} ${m.cx},${m.cz} from ${m.origin}: identical"); return }
        syncsMismatched.incrementAndGet()
        net.fissionmc.net.trace.Trace.emit("ghost.sync.mismatch") { str("from", m.origin); str("dim", m.dimension); int("cx", m.cx); int("cz", m.cz); ints("sections", bad.map { it.index }.toIntArray()) }
        if (net.fissionmc.net.FissionDebug.on) log.info("[fission] ghost sync ${m.dimension} ${m.cx},${m.cz}: ${m.origin}'s copy differs in sections ${bad.map { it.index }}, sending the owner's blocks")
        NodeBootstrap.link?.takeIf { it.isOpen }?.send(GhostFix(RegionsBackend.youId(), m.origin, m.dimension, m.cx, m.cz, bad))
    }

    // ---- viewer
    private fun onFix(server: MinecraftServer, m: GhostFix) {
        val level = server.allLevels.firstOrNull { it.dimension().identifier().toString() == m.dimension } ?: return
        if (RegionsBackend.ownsChunk(level, m.cx, m.cz)) return   // never overwrite our own chunk with somebody's copy
        val chunk = level.chunkSource.getChunkNow(m.cx, m.cz) ?: return
        var fixed = 0
        for (s in m.sections) {
            if (s.index !in 0 until chunk.sectionsCount) continue
            val ids = try { SectionCodec.decode(s.runs) } catch (t: IllegalArgumentException) { log.warning("[fission] ghost fix ${m.dimension} ${m.cx},${m.cz}: $t"); continue }
            val mine = stateIds(chunk, s.index)
            val minY = chunk.minSectionY * 16 + s.index * 16
            var i = 0
            for (y in 0 until 16) for (z in 0 until 16) for (x in 0 until 16) {
                if (ids[i] != mine[i] && fixed < MAX_FIX_BLOCKS) {
                    val pos = BlockPos(m.cx * 16 + x, minY + y, m.cz * 16 + z)
                    Replication.applyRemote(level, pos, Block.stateById(ids[i])); fixed++
                    if (net.fissionmc.net.FissionDebug.on) log.info("[fission] ghost fix ${m.dimension} ${pos.x},${pos.y},${pos.z}: ${Block.stateById(mine[i])} -> ${Block.stateById(ids[i])}")
                }
                i++
            }
        }
        blocksFixed.addAndGet(fixed.toLong())
        net.fissionmc.net.trace.Trace.emit("ghost.fix.applied") { str("from", m.origin); str("dim", m.dimension); int("cx", m.cx); int("cz", m.cz); int("sections", m.sections.size); int("blocks", fixed) }
        if (fixed > 0) log.info("[fission] ghost sync: ${m.origin} owns ${m.dimension} chunk ${m.cx},${m.cz}; $fixed blocks of this ghost copy were out of date and are repaired")
        scheduler.recheck(ChunkPos.pack(m.cx, m.cz))
    }
}
