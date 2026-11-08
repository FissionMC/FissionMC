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
    address: NodeAddress,
    worldRoot: Path,
    val registry: NodeRegistry = NodeRegistry(),
    private val onMessage: (RegisteredNode, NodeMessage) -> Unit = { _, _ -> },
    private val plugins: net.fissionmc.net.plugins.PluginServer? = null,
) : Closeable {
    private val worlds = WorldServer(worldRoot)
    private var bound: Closeable? = null
    @Volatile var address: NodeAddress = address
        private set

    fun start(): MasterServer { bound = transport.bind(address, ::accept); return this }

    private fun accept(link: NodeLink) {
        link.handler = { msg -> if (!worlds.handle(link, msg) && !handlePlugins(link, msg)) registry[linkOwner(link)]?.let { onMessage(it, msg) } }
        link.ready.thenAccept { peer ->
            if (peer.role == NodeRole.BACKEND) { owners[link] = peer.nodeId; registry.register(peer, link) }
            else link.close("masters cannot connect to a master")
        }
        link.closed.thenRun { owners.remove(link) }
    }

    /**
     * Listens on [to] from now on (config reload). The new endpoint is bound first; when that fails because the port is the same (only the host changed) the old one
     * is closed and the new one bound, with the old one restored if that fails too. Links that are already connected stay up. Returns null on success, else why not.
     */
    @Synchronized fun rebind(to: NodeAddress): String? {
        if (to == address) return null
        val old = bound
        try {
            bound = transport.bind(to, ::accept); address = to; old?.close(); return null
        } catch (first: Throwable) {
            val samePort = to is NodeAddress.Tcp && address is NodeAddress.Tcp && (address as NodeAddress.Tcp).port == to.port
            if (!samePort) return first.message ?: first.toString()
            old?.close()
            return try { bound = transport.bind(to, ::accept); address = to; null }
            catch (second: Throwable) { bound = runCatching { transport.bind(address, ::accept) }.getOrNull(); second.message ?: second.toString() }
        }
    }

    /** Plugin replication; a master without a plugin server (combined mode) answers "disabled" so a remote backend does not wait. */
    private fun handlePlugins(link: NodeLink, msg: NodeMessage): Boolean {
        plugins?.let { return it.handle(link, msg) }
        if (msg is net.fissionmc.protocol.PluginManifestRequest) { link.send(net.fissionmc.protocol.PluginManifest(false, emptyList())); return true }
        return msg is net.fissionmc.protocol.PluginFileRequest
    }

    private val owners = java.util.concurrent.ConcurrentHashMap<NodeLink, String>()
    private fun linkOwner(link: NodeLink) = owners[link] ?: ""

    override fun close() { bound?.close(); worlds.close(); plugins?.close() }
}
