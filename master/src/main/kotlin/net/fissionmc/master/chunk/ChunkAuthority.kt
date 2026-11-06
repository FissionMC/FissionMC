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
) {
    private class Lease(val node: String, var expiresAt: Long)
    private class Waiter(val node: RegisteredNode, val reqId: Long)
    private val lock = Any()
    private val leases = HashMap<ChunkKey, Lease>()
    private val waiting = HashMap<ChunkKey, MutableList<Waiter>>()
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
            is ChunkRequest -> request(node, msg)
            is ChunkPush -> push(node, msg)
            else -> return false
        }
        return true
    }

    private fun request(node: RegisteredNode, m: ChunkRequest) {
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
                if (first) { generateJobs.incrementAndGet(); log("[chunks] generate ${key.dimension} ${key.x},${key.z} -> $genOwner (wanted by ${node.info.nodeId})"); registry[genOwner]?.link?.send(GenerateChunk(key.dimension, key.x, key.z)) }
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
        log("[chunks] lease ${key.dimension} ${key.x},${key.z} -> ${node.info.nodeId}")
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
        bytesIn.addAndGet(m.bytes.size.toLong())
        synchronized(lock) { progress[node.info.nodeId] = System.currentTimeMillis() }
        val key = ChunkKey(m.dimension, m.kind, m.x, m.z)
        if (m.kind == ChunkKind.CHUNK) generationOwner(m.dimension, m.x, m.z)?.takeIf { it != node.info.nodeId && registry[it]?.link?.isOpen == true }?.let { owner ->
            // regions: only the generation owner writes a chunk (a neighbour may have advanced it in memory to finish its own border features: its copy is not kept)
            pushesRejected.incrementAndGet(); pushesNotOwner.incrementAndGet(); node.link.send(ChunkStored(m.dimension, m.kind, m.x, m.z, false, store.get(key)?.version ?: 0L)); return
        }
        val r = store.put(key, m.status, m.baseVersion, m.bytes, node.info.nodeId)
        if (r.accepted) pushesAccepted.incrementAndGet() else pushesRejected.incrementAndGet()
        node.link.send(ChunkStored(m.dimension, m.kind, m.x, m.z, r.accepted, r.version))
        if (!r.accepted) return
        registry.broadcast(ChunkAvailable(m.dimension, m.kind, m.x, m.z, r.version), node.info.nodeId)
        // the lease is fulfilled: parked requesters get the data
        val toServe = synchronized(lock) { leases.remove(key); waiting.remove(key) } ?: return
        val stored = store.get(key) ?: return
        toServe.forEach { w -> if (w.node.link.isOpen) send(w.node, stored, w.reqId) }
    }

    /** A backend left: its leases are void, the next waiting backend may generate. */
    val listener = object : NodeRegistry.Listener {
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
