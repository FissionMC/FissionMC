package net.fissionmc.master

import net.fissionmc.net.NodeLink
import net.fissionmc.protocol.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the relay needs from a client connection (implemented by the client session; fakes in tests). */
interface RelayClient {
    val profile: net.fissionmc.master.client.PlayerProfile
    val protocol: Int
    /** A packet frame (id + payload) from the backend for this client. Called on the node link's thread. */
    fun deliver(frame: ByteArray)
    /** The backend session ended; the client should be disconnected. */
    fun detached(reason: String)
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

    /** Attaches [client] to a live backend; returns the player id, or null if no backend is alive. */
    fun attach(client: RelayClient): Long? {
        val live = registry.all.filter { it.link.isOpen }.sortedBy { it.info.nodeId }
        if (live.isEmpty()) return null
        val node = live[Math.floorMod(rr.getAndIncrement(), live.size)]
        val id = ids.getAndIncrement()
        val p = client.profile
        bindings[id] = Binding(id, node, client)
        node.link.send(PlayerAttach(id, p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, p.name,
            p.properties.map { ProfilePropertyData(it.name, it.value, it.signature) }, client.protocol))
        return id
    }

    /** Client frame towards the backend. Returns false when the link is saturated (caller should stop reading the client). */
    fun toBackend(id: Long, frame: ByteArray): Boolean {
        val b = bindings[id] ?: return true
        b.node.link.send(PlayerPacketToBackend(id, frame))
        return b.node.link.isWritable
    }

    fun isWritable(id: Long): Boolean = bindings[id]?.node?.link?.isWritable ?: true

    /** The client went away. */
    fun detach(id: Long, reason: String) {
        val b = bindings.remove(id) ?: return
        if (b.node.link.isOpen) b.node.link.send(PlayerDetach(id, reason))
    }

    fun handle(node: RegisteredNode, msg: NodeMessage): Boolean {
        when (msg) {
            is PlayerPacketToClient -> bindings[msg.playerId]?.takeIf { it.node === node }?.client?.deliver(msg.bytes)
            is PlayerDetach -> bindings[msg.playerId]?.takeIf { it.node === node }?.let { bindings.remove(it.id); it.client.detached(msg.reason) }
            else -> return false
        }
        return true
    }

    /** All players of a backend that left lose their session. */
    val listener = object : NodeRegistry.Listener {
        override fun left(node: RegisteredNode, reason: String) {
            bindings.values.filter { it.node === node }.forEach { bindings.remove(it.id); it.client.detached("backend ${node.info.nodeId} lost: $reason") }
        }
    }
}
