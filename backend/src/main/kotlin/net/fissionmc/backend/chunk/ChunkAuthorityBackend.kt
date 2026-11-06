package net.fissionmc.backend.chunk

import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.logging.Logger
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Backend side of the chunk authority. The `IOWorker` mixin calls [resolve] after every local chunk/entities/poi
 * read and [onStore] for every write; nothing here blocks the server thread, a netty loop or the IO worker: replies complete futures, pushes go out
 * from one pusher thread.
 */
object ChunkAuthorityBackend {
    private val log = Logger.getLogger("FissionMC")
    private data class K(val dim: String, val kind: Int, val x: Int, val z: Int)
    private class Reply(val data: ChunkData?, val lease: Boolean)
    private class Pending(val future: CompletableFuture<Reply>, val startNanos: Long)
    private class Queued(val tag: CompoundTag, val rank: Int)

    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, Pending>()
    /** Version of each chunk blob this backend loaded from / pushed to the master in this session; removed when another backend stores a newer one. */
    private val known = ConcurrentHashMap<K, Long>()
    private val queue = ConcurrentHashMap<K, Queued>()
    private val skip = ThreadLocal<Boolean>()
    @Volatile private var pusherStarted = false
    private const val REQUEST_TIMEOUT_SEC = 600L   // far longer than any lease: the master, not this timeout, decides when a waiting backend generates itself; this only covers a lost answer

    // statistics (logged every 30 s)
    private val fetches = AtomicLong(); private val fetchNanos = AtomicLong(); private val fetchHits = AtomicLong(); private val leasesGot = AtomicLong(); private val timeouts = AtomicLong()
    private val pushed = AtomicLong(); private val pushedBytes = AtomicLong(); private val accepted = AtomicLong(); private val rejected = AtomicLong(); private val pulledBytes = AtomicLong()

    @JvmStatic fun kindOf(type: String): Int = when (type) { "chunk" -> ChunkKind.CHUNK; "entities" -> ChunkKind.ENTITIES; "poi" -> ChunkKind.POI; else -> -1 }

    /** IOWorker mixin: set while the mixin itself writes a chunk it pulled (that write must not be pushed back). */
    @JvmStatic fun skipPush(on: Boolean) { if (on) skip.set(true) else skip.remove() }
    @JvmStatic fun pushing(): Boolean = skip.get() != true

    /** Node link thread. */
    fun onMessage(m: NodeMessage) {
        when (m) {
            is ChunkData -> pending.remove(m.reqId)?.future?.complete(Reply(m, false))
            is ChunkAbsent -> pending.remove(m.reqId)?.future?.complete(Reply(null, m.lease))
            is ChunkStored -> { if (m.accepted) { known[K(m.dimension, m.kind, m.x, m.z)] = m.version; accepted.incrementAndGet() } else rejected.incrementAndGet() }
            is ChunkAvailable -> known.remove(K(m.dimension, m.kind, m.x, m.z))   // another backend has a newer one: the next load of it asks the master again
        }
    }

    /**
     * The local result [local] of a read, made authoritative. With the master reachable it is replaced by the master's chunk; if the master has none
     * and granted the lease, [local] stays (and is pushed when it exists, which migrates old local worlds); without a master [local] stays.
     */
    @JvmStatic @JvmOverloads fun resolve(dim: String, kind: Int, x: Int, z: Int, local: Optional<CompoundTag>, cache: Consumer<CompoundTag>, peek: Boolean = false): CompletableFuture<Optional<CompoundTag>> {
        val key = K(dim, kind, x, z)
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return CompletableFuture.completedFuture(local)
        if (local.isPresent && known.containsKey(key)) return CompletableFuture.completedFuture(local)   // already in sync this session
        startPusher()
        val id = ids.incrementAndGet(); val f = CompletableFuture<Reply>()
        val t0 = System.nanoTime(); pending[id] = Pending(f, t0)
        link.send(ChunkRequest(id, dim, kind, x, z, peek))
        return f.orTimeout(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS).handleAsync({ reply, err ->
            pending.remove(id)
            if (reply == null || err != null) { timeouts.incrementAndGet(); log.warning("[fission] chunk $dim $kind $x,$z: no answer from the master (${err?.javaClass?.simpleName}), using the local result"); return@handleAsync local }
            fetches.incrementAndGet(); fetchNanos.addAndGet(System.nanoTime() - t0)
            val d = reply.data
            if (d != null) {
                fetchHits.incrementAndGet(); pulledBytes.addAndGet(d.bytes.size.toLong())
                try {
                    val tag = decode(d.bytes); known[key] = d.version
                    cache.accept(tag)
                    Optional.of(tag)
                } catch (t: Throwable) { log.warning("[fission] chunk $dim $kind $x,$z from the master is unreadable ($t), using the local result"); local }
            } else {
                if (reply.lease) leasesGot.incrementAndGet()
                if (local.isPresent && !peek) onStore(dim, kind, x, z, local.get())    // a chunk only this backend has: it becomes the master's
                local
            }
        }, net.minecraft.util.Util.ioPool())
    }

    /** Structure scans of unloaded neighbours: the master's data if it has any (no lease, no waiting, nothing cached). */
    @JvmStatic fun peek(dim: String, kind: Int, x: Int, z: Int): CompletableFuture<Optional<CompoundTag>> =
        resolve(dim, kind, x, z, Optional.empty(), Consumer { }, true)

    private var saveTicks = 0
    /** Server thread, every tick: queue unsaved chunks every 5 s so freshly generated (partial) chunks reach the master while other backends may be waiting for them. */
    @JvmStatic fun tick(server: net.minecraft.server.MinecraftServer) {
        if (!pusherStarted || ++saveTicks % 100 != 0 || NodeBootstrap.link?.isOpen != true) return
        for (level in server.allLevels) try { level.chunkSource.save(false); (level.chunkSource.chunkMap as PartialSaver).`fission$savePartial`() } catch (t: Throwable) { log.warning("[fission] periodic chunk save failed: $t") }
    }

    /** IOWorker mixin: a chunk/entities/poi tag is being stored locally; queue it for the master. */
    @JvmStatic fun onStore(dim: String, kind: Int, x: Int, z: Int, tag: CompoundTag) {
        val rank = if (kind == ChunkKind.CHUNK) ChunkStatusRank.of(tag.getStringOr("Status", "")) else 0
        queue[K(dim, kind, x, z)] = Queued(tag, rank)
        startPusher()
    }

    private fun decode(bytes: ByteArray): CompoundTag = DataInputStream(InflaterInputStream(bytes.inputStream()).buffered()).use { NbtIo.read(it, NbtAccounter.unlimitedHeap()) }
    private fun encode(tag: CompoundTag): ByteArray { val o = ByteArrayOutputStream(); DataOutputStream(DeflaterOutputStream(o)).use { NbtIo.write(tag, it) }; return o.toByteArray() }

    @Synchronized private fun startPusher() {
        if (pusherStarted) return
        pusherStarted = true
        val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fission-chunk-push").also { it.isDaemon = true } }
        ex.scheduleWithFixedDelay({ try { drain() } catch (t: Throwable) { log.warning("[fission] chunk push failed: $t") } }, 50, 50, TimeUnit.MILLISECONDS)
        ex.scheduleWithFixedDelay({ logStats(onlyIfChanged = true) }, 10, 10, TimeUnit.SECONDS)
    }

    /** Sends queued chunks while the master link is open and writable (backpressure); unsent ones stay queued, also across a reconnect. */
    private fun drain() {
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        var n = 0
        for (k in queue.keys.toList()) {
            if (!link.isWritable || n >= 200) return
            val q = queue.remove(k) ?: continue
            val bytes = encode(q.tag)
            if (bytes.size > MAX_CHUNK_BLOB) { log.warning("[fission] chunk ${k.dim} ${k.x},${k.z} is ${bytes.size} bytes: not pushed"); continue }
            link.send(ChunkPush(k.dim, k.kind, k.x, k.z, q.rank, known[k] ?: 0L, bytes))
            pushed.incrementAndGet(); pushedBytes.addAndGet(bytes.size.toLong()); n++
        }
    }

    /** IOWorker.close: wait (bounded) for the queue to reach the master before the level goes away. */
    @JvmStatic fun flush(timeoutMs: Long) {
        if (!pusherStarted) return
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (queue.isNotEmpty() && NodeBootstrap.link?.isOpen == true && System.nanoTime() < end) Thread.sleep(10)
        if (queue.isNotEmpty()) log.warning("[fission] ${queue.size} chunks were not pushed before shutdown")
        logStats()
    }

    private var lastStats = ""
    private fun logStats(onlyIfChanged: Boolean = false) {
        val f = fetches.get()
        val line = statsLine(f)
        if (onlyIfChanged && line == lastStats) return
        lastStats = line
        log.info(line)
    }

    private fun statsLine(f: Long): String =
        ("[fission] chunk authority: $f fetches (${fetchHits.get()} from the master, ${leasesGot.get()} leases, ${timeouts.get()} timeouts, avg ${if (f == 0L) 0 else fetchNanos.get() / f / 1000} us, ${pulledBytes.get()} bytes in); " +
            "pushes ${pushed.get()} (${pushedBytes.get()} bytes, accepted ${accepted.get()} rejected ${rejected.get()}), queued ${queue.size}")
}
