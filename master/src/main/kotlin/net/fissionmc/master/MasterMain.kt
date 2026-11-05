package net.fissionmc.master

import net.fissionmc.net.*
import net.fissionmc.protocol.MC_VERSION
import net.fissionmc.master.client.ClientConfig
import net.fissionmc.master.client.ClientServer
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
    val relay = PlayerRelay(registry)
    registry.addListener(relay.listener)
    val master = MasterServer(transport, listen, cfg.worldRoot, registry) { n, m -> if (!relay.handle(n, m)) hub.handle(n, m) }.start()
    master.registry.addListener(object : NodeRegistry.Listener {
        override fun joined(node: RegisteredNode) = println("[master] ${node.info.nodeId} joined")
        override fun left(node: RegisteredNode, reason: String) = println("[master] ${node.info.nodeId} left: $reason")
    })
    println("[master] event log epoch ${hub.epoch}")
    println("[master] listening on ${listen.host}:${listen.port}, worlds in ${cfg.worldRoot}")
    val clientCfg = ClientConfig.load(Path.of(args.firstOrNull() ?: "fission.properties"))
    val clients = if (clientCfg.enabled) ClientServer(clientCfg, relay = relay).start() else null
    registry.addListener(object : NodeRegistry.Listener { override fun joined(node: RegisteredNode) { clients?.attachWaiting() } })
    val props = java.util.Properties().also { pr -> java.nio.file.Files.newBufferedReader(Path.of(args.firstOrNull() ?: "fission.properties")).use(pr::load) }
    if (props.getProperty("debug.relay-probe") == "true")
        registry.addListener(RelayProbe(relay, props.getProperty("debug.record-limbo")?.let { Path.of(it) }))
    Runtime.getRuntime().addShutdownHook(Thread { clients?.close(); master.close(); transport.close() })
    Thread.currentThread().join()
}
