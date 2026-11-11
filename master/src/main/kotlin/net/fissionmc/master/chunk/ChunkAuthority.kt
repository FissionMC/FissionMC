package net.fissionmc.master.chunk

import net.fissionmc.master.NodeRegistry
import net.fissionmc.master.RegisteredNode
import net.fissionmc.protocol.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The master's side of the chunk protocol: answers [ChunkRequest]s from the [ChunkStore], arbitrates generation
 * leases (first requester generates, the others wait for the push), stores [ChunkPush]es and tells the other backends.
 */
class ChunkAuthority(
    private val store: ChunkStore, private val registry: NodeRegistry,
    private val timer: ScheduledExecutorService, private val log: (String) -> Unit = {}, private val leaseMs: Long = 60_000,
    /** Worlds whose generator is deterministic and feature-free (void, flat): absent chunks need no lease, every backend generates them identically. */
    private val deterministic: (String) -> Boolean = { false },
    /** Static regions: who generates a chunk (null = everybody, the first requester takes the lease). */
    private val generationOwner: (dimension: String, x: Int, z: Int) -> String? = { _, _, _ -> null },
    /** Static regions: may the non-owner [requester] wait for the owner to generate this chunk (a player of the requester can see it: a ghost chunk)? Else the requester generates it locally and does not keep it. */
    private val ownerGenerates: (requester: String, dimension: String, x: Int, z: Int) -> Boolean = { _, _, _, _ -> true },
    /** Static regions: the backend that owns (ticks) chunk x,z, null = nobody in particular. Entities and points of interest of a chunk are only accepted from it. */
    private val regionOwner: (dimension: String, x: Int, z: Int) -> String? = { _, _, _ -> null },
    /** Where requests and pushes run (they read and write the store on disk). The master gives a thread of its own, so chunk IO never blocks the network thread that also relays players' packets. */
    private val executor: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
) {
    private class Lease(val node: String, var expiresAt: Long)
    private class Waiter(val node: RegisteredNode, val reqId: Long)
    private val lock = Any()
    private val leases = HashMap<ChunkKey, Lease>()
    private val waiting = HashMap<ChunkKey, MutableList<Waiter>>()
    /** Generation jobs given to a region owner (GenerateChunk) that have not been pushed yet. They are sent again while requesters wait, and fall back to the first waiter when the owner is gone. */
    private class Job(val owner: String, var sentAt: Long)
    private val jobs = HashMap<ChunkKey, Job>()

    init { timer.scheduleWithFixedDelay({ try { retryJobs() } catch (t: Throwable) { log("[chunks] job check failed: $t") } }, 5, 5, TimeUnit.SECONDS) }

    internal fun retryJobs(now: Long = System.currentTimeMillis()) = synchronized(lock) {
        for ((key, job) in jobs.entries.toList()) {
            if (waiting[key].isNullOrEmpty()) { jobs.remove(key); continue }
            val link = registry[job.owner]?.link?.takeIf { it.isOpen }
            if (link != null) { if (now - job.sentAt >= 10_000) { job.sentAt = now; link.send(GenerateChunk(key.dimension, key.x, key.z)) } }   // lost, or the owner was not ticking yet: ask again
            else if (now - job.sentAt >= 5_000) ownerGone(key)
        }
    }

    /** The owner of a generation job is not connected: the first waiting backend generates the chunk instead (as if it had asked while the owner was away). */
    private fun ownerGone(key: ChunkKey) {
        jobs.remove(key)
        val ws = waiting[key] ?: return
        while (ws.isNotEmpty()) { val w = ws.removeAt(0); if (w.node.link.isOpen) { grant(key, w.node, w.reqId); timer.schedule({ leaseExpiry(key) }, leaseMs + 50, TimeUnit.MILLISECONDS); break } }
        if (ws.isEmpty()) waiting.remove(key)
    }
    /** Last time a backend pushed anything: a holder that keeps pushing is alive and generating, so its leases do not run out (they would make a waiting backend generate the same chunks again). */
    private val progress = HashMap<String, Long>()

    private fun validUntil(l: Lease): Long = maxOf(l.expiresAt, (progress[l.node] ?: 0L) + leaseMs)

    // counters for the log / tests
    val requests = AtomicLong(); val served = AtomicLong(); val leasesGranted = AtomicLong(); val parked = AtomicLong(); val leaseExpired = AtomicLong()
    val generateJobs = AtomicLong(); val pushesNotOwner = AtomicLong(); val pushesAccepted = AtomicLong(); val pushesRejected = AtomicLong(); val bytesIn = AtomicLong(); val bytesOut = AtomicLong()

    fun statsLine(): String = "[chunks] store ${store.count()} blobs; requests ${requests.get()} served ${served.get()} leases ${leasesGranted.get()} parked ${parked.get()} expired ${leaseExpired.get()}; " +
        "pushes accepted ${pushesAccepted.get()} rejected ${pushesRejected.get()}; bytes in ${bytesIn.get()} out ${bytesOut.get()}; generate jobs ${generateJobs.get()} pushes refused (not the owner) ${pushesNotOwner.get()}"

    fun handle(node: RegisteredNode, msg: NodeMessage): Boolean {
        when (msg) {
            is ChunkRequest -> executor.execute { try { request(node, msg) } catch (t: Throwable) { log("[chunks] request ${msg.dimension} ${msg.x},${msg.z} from ${node.info.nodeId} failed: $t"); node.link.send(ChunkAbsent(msg.reqId, false)) } }
            is ChunkPush -> executor.execute { try { push(node, msg) } catch (t: Throwable) { log("[chunks] push ${msg.dimension} ${msg.x},${msg.z} from ${node.info.nodeId} failed: $t") } }
            else -> return false
        }
        return true
    }

    private fun request(node: RegisteredNode, m: ChunkRequest) {
        net.fissionmc.net.trace.Trace.emit("chunk.request") { str("node", node.info.nodeId); str("dim", m.dimension); int("kind", m.kind); int("x", m.x); int("z", m.z); bool("peek", m.peek); long("req", m.reqId) }
        requests.incrementAndGet()
        val key = ChunkKey(m.dimension, m.kind, m.x, m.z)
        val have = store.get(key)
        if (have != null) { send(node, have, m.reqId); return }
        if (m.peek || m.kind != ChunkKind.CHUNK || deterministic(m.dimension)) { node.link.send(ChunkAbsent(m.reqId, false)); return }   // entities/poi are created with the chunk, deterministic worlds are generated locally: no lease
        val genOwner = generationOwner(m.dimension, m.x, m.z)?.takeIf { registry[it]?.link?.isOpen == true }   // an owner that is not connected cannot generate: the requester does
        if (genOwner != null && genOwner != node.info.nodeId && !ownerGenerates(node.info.nodeId, m.dimension, m.x, m.z)) { node.link.send(ChunkAbsent(m.reqId, false)); return }   // far from any border: local, unkept
        if (genOwner != null && genOwner != node.info.nodeId) {
            // somebody else owns this chunk: park the request, the owner generates it (GenerateChunk) and its push serves everybody who waits
            synchronized(lock) {
                parked.incrementAndGet()
                val first = waiting[key].isNullOrEmpty() && leases[key] == null
                waiting.computeIfAbsent(key) { ArrayList() } += Waiter(node, m.reqId)
                if (first) { jobs[key] = Job(genOwner, System.currentTimeMillis()); generateJobs.incrementAndGet(); if (net.fissionmc.net.FissionDebug.on) log("[chunks] generate ${key.dimension} ${key.x},${key.z} -> $genOwner (wanted by ${node.info.nodeId})"); registry[genOwner]?.link?.send(GenerateChunk(key.dimension, key.x, key.z)) }
            }
            return
        }
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val l = leases[key]
            if (l == null || validUntil(l) < now || l.node == node.info.nodeId) {
                if (l != null && validUntil(l) < now && l.node != node.info.nodeId) leaseExpired.incrementAndGet()
                grant(key, node, m.reqId)
            } else {
                parked.incrementAndGet()
                waiting.computeIfAbsent(key) { ArrayList() } += Waiter(node, m.reqId)
                timer.schedule({ leaseExpiry(key) }, validUntil(l) - now + 50, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun grant(key: ChunkKey, node: RegisteredNode, reqId: Long) {
        leases[key] = Lease(node.info.nodeId, System.currentTimeMillis() + leaseMs)
        leasesGranted.incrementAndGet()
        net.fissionmc.net.trace.Trace.emit("chunk.lease") { str("dim", key.dimension); int("x", key.x); int("z", key.z); str("node", node.info.nodeId); long("req", reqId) }
        if (net.fissionmc.net.FissionDebug.on) log("[chunks] lease ${key.dimension} ${key.x},${key.z} -> ${node.info.nodeId}")
        node.link.send(ChunkAbsent(reqId, true))
    }

    /** A lease ran out without a push: the next parked requester gets it. */
    private fun leaseExpiry(key: ChunkKey): Unit = synchronized(lock) {
        val l = leases[key] ?: return@synchronized
        val now = System.currentTimeMillis()
        if (validUntil(l) >= now) { timer.schedule({ leaseExpiry(key) }, validUntil(l) - now + 50, TimeUnit.MILLISECONDS); return@synchronized }   // the holder made progress meanwhile
        leases.remove(key); leaseExpired.incrementAndGet()
        val ws = waiting[key] ?: return@synchronized
        while (ws.isNotEmpty()) {
            val w = ws.removeAt(0)
            if (!w.node.link.isOpen) continue
            grant(key, w.node, w.reqId); break
        }
        if (ws.isEmpty()) waiting.remove(key)
    }

    private fun push(node: RegisteredNode, m: ChunkPush) {
        net.fissionmc.net.trace.Trace.emit("chunk.push") { str("node", node.info.nodeId); str("dim", m.dimension); int("kind", m.kind); int("x", m.x); int("z", m.z); long("base", m.baseVersion); blob("data", m.bytes) }
        bytesIn.addAndGet(m.bytes.size.toLong())
        synchronized(lock) { progress[node.info.nodeId] = System.currentTimeMillis() }
        val key = ChunkKey(m.dimension, m.kind, m.x, m.z)
        if (m.kind == ChunkKind.CHUNK) generationOwner(m.dimension, m.x, m.z)?.takeIf { it != node.info.nodeId && registry[it]?.link?.isOpen == true }?.let { owner ->
            // regions: only the generation owner writes a chunk (a neighbour may have advanced it in memory to finish its own border features: its copy is not kept)
            pushesRejected.incrementAndGet(); pushesNotOwner.incrementAndGet(); node.link.send(ChunkStored(m.dimension, m.kind, m.x, m.z, false, store.get(key)?.version ?: 0L)); return
        }
        if (m.kind != ChunkKind.CHUNK) regionOwner(m.dimension, m.x, m.z)?.takeIf { it != node.info.nodeId && registry[it]?.link?.isOpen == true }?.let {
            // a ghost copy never has live entities: its (empty or stale) entity and poi data must not overwrite the owner's
            pushesRejected.incrementAndGet(); pushesNotOwner.incrementAndGet(); node.link.send(ChunkStored(m.dimension, m.kind, m.x, m.z, false, store.get(key)?.version ?: 0L)); return
        }
        val r = store.put(key, m.status, m.baseVersion, m.bytes, node.info.nodeId)
        if (r.accepted) pushesAccepted.incrementAndGet() else pushesRejected.incrementAndGet()
        node.link.send(ChunkStored(m.dimension, m.kind, m.x, m.z, r.accepted, r.version))
        if (!r.accepted) return
        registry.broadcast(ChunkAvailable(m.dimension, m.kind, m.x, m.z, r.version), node.info.nodeId)
        // the lease is fulfilled: parked requesters get the data
        val toServe = synchronized(lock) { leases.remove(key); jobs.remove(key); waiting.remove(key) } ?: return
        val stored = store.get(key) ?: return
        toServe.forEach { w -> if (w.node.link.isOpen) send(w.node, stored, w.reqId) }
    }

    /** A backend left: its leases are void, the next waiting backend may generate. */
    val listener = object : NodeRegistry.Listener {
        /** An owner (re)connected: the jobs it still owes are sent again at once. */
        override fun joined(node: RegisteredNode) = synchronized(lock) {
            for ((key, job) in jobs) if (job.owner == node.info.nodeId && !waiting[key].isNullOrEmpty()) { job.sentAt = System.currentTimeMillis(); node.link.send(GenerateChunk(key.dimension, key.x, key.z)) }
        }
        override fun left(node: RegisteredNode, reason: String) = synchronized(lock) {
            progress.remove(node.info.nodeId)
            for (key in leases.filterValues { it.node == node.info.nodeId }.keys.toList()) {
                leases.remove(key)
                val ws = waiting[key] ?: continue
                while (ws.isNotEmpty()) { val w = ws.removeAt(0); if (w.node.link.isOpen) { grant(key, w.node, w.reqId); break } }
                if (ws.isEmpty()) waiting.remove(key)
            }
        }
    }

    private fun send(node: RegisteredNode, c: StoredChunk, reqId: Long) {
        served.incrementAndGet(); bytesOut.addAndGet(c.bytes.size.toLong())
        node.link.send(ChunkData(reqId, c.status, c.version, c.bytes))
    }
}
