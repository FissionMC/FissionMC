package net.fissionmc.master

import net.fissionmc.net.*
import net.fissionmc.protocol.MC_VERSION
import java.nio.file.Path

/** Standalone master process: `fission.properties` with node.mode=master, node.listen, node.authorized, node.world-root. */
fun main(args: Array<String>) {
    val cfg = NodeConfig.load(Path.of(args.firstOrNull() ?: "fission.properties"))
    require(cfg.masterProcess) { "fission.properties must set node.mode=master" }
    val listen = cfg.listen!!
    val transport = NettyTransport(NodeSupport.masterNode(MC_VERSION), security = NodeSupport.masterSecurity(cfg))
    val registry = NodeRegistry()
    val hub = ReplicationHub()
    registry.addListener(hub.listener)
    val master = MasterServer(transport, listen, cfg.worldRoot, registry) { n, m -> hub.handle(n, m) }.start()
    master.registry.addListener(object : NodeRegistry.Listener {
        override fun joined(node: RegisteredNode) = println("[master] ${node.info.nodeId} joined")
        override fun left(node: RegisteredNode, reason: String) = println("[master] ${node.info.nodeId} left: $reason")
    })
    println("[master] event log epoch ${hub.epoch}")
    println("[master] listening on ${listen.host}:${listen.port}, worlds in ${cfg.worldRoot}")
    Runtime.getRuntime().addShutdownHook(Thread { master.close(); transport.close() })
    Thread.currentThread().join()
}
