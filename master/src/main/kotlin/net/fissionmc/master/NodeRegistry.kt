package net.fissionmc.master

import net.fissionmc.net.NodeLink
import net.fissionmc.net.PeerInfo
import net.fissionmc.protocol.NodeMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class RegisteredNode(val info: PeerInfo, val link: NodeLink)

/** The master's view of which backends are connected. A new link for a known node id replaces the old one. */
class NodeRegistry {
    private val nodes = ConcurrentHashMap<String, RegisteredNode>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    interface Listener {
        fun joined(node: RegisteredNode) {}
        fun left(node: RegisteredNode, reason: String) {}
    }

    fun addListener(l: Listener) { listeners += l }
    operator fun get(nodeId: String): RegisteredNode? = nodes[nodeId]
    val all: Collection<RegisteredNode> get() = nodes.values

    fun register(info: PeerInfo, link: NodeLink) {
        val node = RegisteredNode(info, link)
        nodes.put(info.nodeId, node)?.let { old -> if (old.link !== link) old.link.close("replaced by new connection") }
        listeners.forEach { it.joined(node) }
        link.closed.thenAccept { reason ->
            if (nodes.remove(info.nodeId, node)) listeners.forEach { it.left(node, reason) }
        }
    }

    fun broadcast(msg: NodeMessage, except: String? = null) {
        for ((id, n) in nodes) if (id != except && n.link.isOpen) n.link.send(msg)
    }
}
