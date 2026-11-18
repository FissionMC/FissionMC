package net.fissionmc.master

import net.fissionmc.net.NodeLink
import net.fissionmc.net.PeerInfo
import net.fissionmc.protocol.NodeMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class RegisteredNode(val info: PeerInfo, val link: NodeLink, ready: Boolean = true) {
    val since: Long = System.nanoTime()
    /**
     * False from the link's handshake until the backend says [net.fissionmc.protocol.NodeReady] (its server loop runs): a booting backend is connected but cannot log a relayed player in,
     * so nothing is started towards it (see [PlayerRelay.attach]). Nodes registered without a readiness protocol (tests, [register] default) are ready at once.
     */
    @Volatile var ready: Boolean = ready; internal set
    /** Milliseconds from the link's handshake to its NodeReady, -1 while booting. */
    @Volatile var readyAfterMs: Long = if (ready) 0 else -1; internal set
    /** Milliseconds one game tick of this backend takes right now (50 = 20 tps; the backend reports it, see NodeTickRate). Timeouts for what its game thread must answer are multiplied by net.fissionmc.protocol.TickScale. */
    @Volatile var tickMs: Int = net.fissionmc.protocol.TickScale.NORMAL_MS
    fun scaled(baseMs: Long): Long = net.fissionmc.protocol.TickScale.scale(baseMs, tickMs)
}

/** The master's view of which backends are connected. A new link for a known node id replaces the old one. */
class NodeRegistry {
    private val nodes = ConcurrentHashMap<String, RegisteredNode>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    interface Listener {
        fun joined(node: RegisteredNode) {}
        fun left(node: RegisteredNode, reason: String) {}
        /** The backend reported that it can take players (also called for a node that registered ready). */
        fun ready(node: RegisteredNode) {}
    }

    /** Links that replaced a still registered one of the same node id. */
    val replacements = java.util.concurrent.atomic.AtomicLong()
    fun addListener(l: Listener) { listeners += l }
    operator fun get(nodeId: String): RegisteredNode? = nodes[nodeId]
    val all: Collection<RegisteredNode> get() = nodes.values

    fun register(info: PeerInfo, link: NodeLink, ready: Boolean = true) {
        val node = RegisteredNode(info, link, ready)
        val old = nodes.put(info.nodeId, node)
        if (old != null && old.link !== link) {
            replacements.incrementAndGet()
            // a node id that is replaced again and again within seconds is probably used by two processes (a cloned folder with its .fission-node-id and key): each registration kicks the other one
            if (System.nanoTime() - old.since < 10_000_000_000L) net.fissionmc.net.pairing.Audit.failure("dupid:${info.nodeId}", "node id ${info.nodeId} was replaced by a new link only ${(System.nanoTime() - old.since) / 1_000_000} ms after it registered: is a second process (a cloned backend folder) using the same node id?")
            // the old registration's closed-callback no longer finds itself in the map, so it must be told it left here
            old.link.close("replaced by new connection")
            listeners.forEach { it.left(old, "replaced by new connection") }
        }
        listeners.forEach { it.joined(node) }
        if (ready) listeners.forEach { it.ready(node) }
        link.closed.thenAccept { reason ->
            if (nodes.remove(info.nodeId, node)) listeners.forEach { it.left(node, reason) }
        }
    }

    /** The node's backend reported that it is ready (stale links, i.e. a node that was replaced meanwhile, are ignored). */
    fun markReady(node: RegisteredNode) {
        if (nodes[node.info.nodeId] !== node || node.ready) return
        node.readyAfterMs = (System.nanoTime() - node.since) / 1_000_000; node.ready = true
        listeners.forEach { it.ready(node) }
    }

    fun broadcast(msg: NodeMessage, except: String? = null) {
        for ((id, n) in nodes) if (id != except && n.link.isOpen) n.link.send(msg)
    }
}
