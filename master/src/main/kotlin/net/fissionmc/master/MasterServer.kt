package net.fissionmc.master

import net.fissionmc.net.*
import net.fissionmc.net.world.WorldServer
import net.fissionmc.protocol.NodeMessage
import net.fissionmc.protocol.NodeRole
import java.io.Closeable
import java.nio.file.Path

/**
 * Accepts backend links, tracks them in [registry], serves worlds from [worldRoot].
 * Messages nothing here handles go to [onMessage] (replication, packet routing, ... later).
 * Works unchanged on [NodeAddress.Local] (hosted inside a backend's JVM) and [NodeAddress.Tcp].
 */
class MasterServer(
    private val transport: Transport,
    private val address: NodeAddress,
    worldRoot: Path,
    val registry: NodeRegistry = NodeRegistry(),
    private val onMessage: (RegisteredNode, NodeMessage) -> Unit = { _, _ -> },
) : Closeable {
    private val worlds = WorldServer(worldRoot)
    private var bound: Closeable? = null

    fun start(): MasterServer {
        bound = transport.bind(address) { link ->
            link.handler = { msg -> if (!worlds.handle(link, msg)) registry[linkOwner(link)]?.let { onMessage(it, msg) } }
            link.ready.thenAccept { peer ->
                if (peer.role == NodeRole.BACKEND) { owners[link] = peer.nodeId; registry.register(peer, link) }
                else link.close("masters cannot connect to a master")
            }
            link.closed.thenRun { owners.remove(link) }
        }
        return this
    }

    private val owners = java.util.concurrent.ConcurrentHashMap<NodeLink, String>()
    private fun linkOwner(link: NodeLink) = owners[link] ?: ""

    override fun close() { bound?.close(); worlds.close() }
}
