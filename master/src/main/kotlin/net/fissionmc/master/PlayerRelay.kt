package net.fissionmc.master

import net.fissionmc.net.NodeLink
import net.fissionmc.protocol.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the relay needs from a client connection (implemented by the client session; fakes in tests). */
interface RelayClient {
    val profile: net.fissionmc.master.client.PlayerProfile
    val protocol: Int
    /** The client's real address and the host name it connected with (forwarded to the backend), if known. */
    val remoteAddress: java.net.InetSocketAddress? get() = null
    val virtualHost: String get() = ""
    /** A packet frame (id + payload) from the backend for this client. Called on the node link's thread. */
    fun deliver(frame: ByteArray)
    /** Like [deliver], but says which binding (player id of an [PlayerRelay.attach]) the frame came from; a client with two bindings (backend switch) overrides this. */
    fun deliverFrom(bindingId: Long, frame: ByteArray) = deliver(frame)
    /** Like [detached], for a client that may hold two bindings at once. */
    fun detachedFrom(bindingId: Long, reason: String, backendLost: Boolean = false) = detached(reason, backendLost)
    /**
     * The backend session ended. [backendLost] is true when the backend itself went away (the client may be sent back to limbo);
     * otherwise the backend closed/kicked the player; [reason] starting with `kick:` carries a message for the client.
     */
    fun detached(reason: String, backendLost: Boolean = false)
}

/**
 * Master side of the player relay: picks a backend (round robin over live nodes) and moves packet frames between
 * client sessions and backends over the node link. Frames are opaque here; PacketEvents listeners (P3) hook in at the client session.
 */
class PlayerRelay(private val registry: NodeRegistry) {
    private class Binding(val id: Long, val node: RegisteredNode, val client: RelayClient)
    private val ids = AtomicLong(1)
    private val bindings = ConcurrentHashMap<Long, Binding>()
    private val rr = java.util.concurrent.atomic.AtomicInteger()

    val attached: Int get() = bindings.size

    // relay counters for MasterStats (client -> backend = "in")
    val framesIn = java.util.concurrent.atomic.AtomicLong(); val bytesIn = java.util.concurrent.atomic.AtomicLong()
    val framesOut = java.util.concurrent.atomic.AtomicLong(); val bytesOut = java.util.concurrent.atomic.AtomicLong()

    /** Relayed players per backend node id. */
    fun playersByNode(): Map<String, Int> = bindings.values.groupingBy { it.node.info.nodeId }.eachCount()

    /** Node id a binding talks to (null once detached). */
    fun nodeIdOf(id: Long): String? = bindings[id]?.node?.info?.nodeId

    /** Binding ids of all players currently attached (any backend), with their node id. */
    fun bindingNodes(): Map<Long, String> = bindings.mapValues { it.value.node.info.nodeId }

    /**
     * Attaches [client] to a live backend; returns the player id, or null if no backend is alive. With [nodeId] the named backend is used
     * (null when that one is not alive), otherwise round robin.
     */
    fun attach(client: RelayClient, nodeId: String? = null): Long? {
        val live = registry.all.filter { it.link.isOpen }.sortedBy { it.info.nodeId }
        if (live.isEmpty()) return null
        val node = if (nodeId != null) live.firstOrNull { it.info.nodeId == nodeId } ?: return null else live[Math.floorMod(rr.getAndIncrement(), live.size)]
        val id = ids.getAndIncrement()
        val p = client.profile
        bindings[id] = Binding(id, node, client)
        client.remoteAddress?.let { a -> node.link.send(PlayerClientInfo(id, a.address?.hostAddress ?: a.hostString, a.port, client.virtualHost)) }
        node.link.send(PlayerAttach(id, p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, p.name,
            p.properties.map { ProfilePropertyData(it.name, it.value, it.signature) }, client.protocol))
        return id
    }

    /** Client frame towards the backend. Returns false when the link is saturated (caller should stop reading the client). */
    fun toBackend(id: Long, frame: ByteArray): Boolean {
        val b = bindings[id] ?: return true
        b.node.link.send(PlayerPacketToBackend(id, frame)); framesIn.incrementAndGet(); bytesIn.addAndGet(frame.size.toLong())
        return b.node.link.isWritable
    }

    /** Sends a control message (not a packet frame) to the backend a binding talks to. */
    fun sendTo(id: Long, msg: NodeMessage) { bindings[id]?.node?.link?.takeIf { it.isOpen }?.send(msg) }

    /** Sends [msg] to every live backend except [nodeId]. */
    fun broadcastExcept(nodeId: String, msg: NodeMessage) { registry.broadcast(msg, nodeId) }

    /** Receives the backends' answers to global chat messages (set by the master main / tests). */
    @Volatile var chatResultHandler: ((GlobalChatResult) -> Unit)? = null

    fun isWritable(id: Long): Boolean = bindings[id]?.node?.link?.isWritable ?: true

    /** The client went away. */
    fun detach(id: Long, reason: String) {
        val b = bindings.remove(id) ?: return
        if (b.node.link.isOpen) b.node.link.send(PlayerDetach(id, reason))
    }

    fun handle(node: RegisteredNode, msg: NodeMessage): Boolean {
        when (msg) {
            is PlayerPacketToClient -> { framesOut.incrementAndGet(); bytesOut.addAndGet(msg.bytes.size.toLong()); bindings[msg.playerId]?.takeIf { it.node === node }?.let { it.client.deliverFrom(it.id, msg.bytes) } }
            is PlayerDetach -> bindings[msg.playerId]?.takeIf { it.node === node }?.let { bindings.remove(it.id); it.client.detachedFrom(it.id, msg.reason) }
            is GlobalChatResult -> chatResultHandler?.invoke(msg)
            else -> return false
        }
        return true
    }

    /** All players of a backend that left lose their session. */
    val listener = object : NodeRegistry.Listener {
        override fun left(node: RegisteredNode, reason: String) {
            bindings.values.filter { it.node === node }.forEach { bindings.remove(it.id); it.client.detachedFrom(it.id, "backend ${node.info.nodeId} lost: $reason", true) }
        }
    }
}
