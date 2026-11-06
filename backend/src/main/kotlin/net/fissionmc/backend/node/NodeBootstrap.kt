package net.fissionmc.backend.node

import net.fissionmc.master.MasterServer
import net.fissionmc.master.NodeRegistry
import net.fissionmc.master.RegisteredNode
import net.fissionmc.master.ReplicationHub
import net.fissionmc.net.*
import net.fissionmc.net.world.WorldPuller
import net.fissionmc.protocol.MC_VERSION
import net.fissionmc.protocol.NodeMessage
import net.fissionmc.protocol.NodeRole
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Starts FissionMC node networking before the server loads its world.
 * Called from `PaperStartup.begin`, i.e. after Paper's options are known and before vanilla `Main.main` runs.
 * In STANDALONE mode (the default) it does nothing.
 */
object NodeBootstrap {
    private val log = Logger.getLogger("FissionMC")
    private const val PULL_TIMEOUT_S = 600L
    private const val CONNECT_TIMEOUT_S = 30L

    @Volatile var link: NodeLink? = null; private set
    @Volatile var master: MasterServer? = null; private set
    private val extraHandlers = java.util.concurrent.CopyOnWriteArrayList<(NodeMessage) -> Unit>()
    private var closeables = mutableListOf<AutoCloseable>()

    /** Later subsystems (replication, packet routing) register here to receive master messages. */
    @JvmStatic fun addMessageHandler(h: (NodeMessage) -> Unit) { extraHandlers += h }

    private val connectListeners = java.util.concurrent.CopyOnWriteArrayList<(NodeLink) -> Unit>()

    /** Called with every master link once the world is pulled: the first connection and every reconnect. */
    @JvmStatic fun addConnectListener(h: (NodeLink) -> Unit) { connectListeners += h }

    /** This backend's node id (`node.id`), set once the world is ready. */
    @JvmStatic @Volatile var nodeId: String = ""

    @Volatile private var started = false

    /** A new master link after the first one (reconnect): route its messages, then tell the listeners. */
    private fun relinked(l: NodeLink) {
        link = l
        l.handler = { msg -> extraHandlers.forEach { it(msg) } }
        connectListeners.forEach { runCatching { it(l) }.onFailure { e -> log.warning("connect listener failed: $e") } }
    }

    @JvmStatic
    fun start(universe: Path, levelName: String) {
        val cfg = NodeConfig.load(Path.of("fission.properties").toAbsolutePath())
        if (cfg.mode == NodeMode.STANDALONE) return
        require(net.fissionmc.net.world.WorldFiles.safeRelative(levelName)) { "level-name '$levelName' is not usable for node sync" }
        val dest = universe.resolve(levelName).toAbsolutePath().normalize()
        require(dest != Path.of("").toAbsolutePath().normalize() && dest != universe.toAbsolutePath().normalize()) { "refusing to sync into $dest" }
        if (cfg.mode == NodeMode.COMBINED)
            require(cfg.worldRoot.resolve(levelName).toAbsolutePath().normalize() != dest) {
                "node.world-root must differ from the backend's own world directory in combined mode (the pull would overwrite its own source)"
            }
        Runtime.getRuntime().addShutdownHook(Thread { stop() })

        val address: NodeAddress = when (cfg.mode) {
            NodeMode.COMBINED -> startCombinedMaster(cfg)
            else -> cfg.master!!
        }
        val transport = NettyTransport(
            LocalNode(cfg.nodeId, NodeRole.BACKEND, MC_VERSION),
            security = if (address is NodeAddress.Tcp) NodeSupport.backendSecurity(cfg) else null,
        )
        closeables += transport

        val ready = java.util.concurrent.CompletableFuture<NodeLink>()
        val client = NodeClient(
            transport, address,
            onReady = { l, peer ->
                log.info("Connected to master ${peer.nodeId}")
                if (!ready.complete(l) && started) relinked(l)
            },
            onFailure = { log.warning("Master connection failed: ${it.message}") },
            onDisconnect = { log.warning("Lost connection to master: $it") },
        ).start()
        closeables += client
        val l = try { ready.get(CONNECT_TIMEOUT_S, TimeUnit.SECONDS) }
        catch (e: java.util.concurrent.TimeoutException) { stop(); error("Could not reach the master at $address within ${CONNECT_TIMEOUT_S}s") }
        link = l

        log.info("Pulling world '$levelName' from the master into $dest")
        val puller = WorldPuller(l, dest)
        l.handler = { msg -> if (!puller.handle(msg)) extraHandlers.forEach { it(msg) } }
        val t0 = System.nanoTime()
        val r = try { puller.pull(levelName).get(PULL_TIMEOUT_S, TimeUnit.SECONDS) }
        catch (e: Exception) { stop(); throw IllegalStateException("World pull failed: ${e.cause?.message ?: e.message}", e) }
        log.info("World ready: ${r.downloaded} downloaded (${r.bytes / 1024} KiB), ${r.unchanged} unchanged, ${r.deleted} removed in ${(System.nanoTime() - t0) / 1_000_000} ms")
        // From here on, messages from the master go to the registered handlers.
        l.handler = { msg -> extraHandlers.forEach { it(msg) } }
        nodeId = cfg.nodeId
        net.fissionmc.backend.replication.Replication.attach(cfg.nodeId)
        net.fissionmc.backend.relay.PlayerRelayBackend.attach()
        started = true
        connectListeners.forEach { it(l) }
    }

    /** Hosts the master in this JVM; returns the in-JVM address the backend should dial. */
    private fun startCombinedMaster(cfg: NodeConfig): NodeAddress {
        val registry = NodeRegistry()
        val hub = ReplicationHub()
        registry.addListener(hub.listener)
        val relay = net.fissionmc.master.PlayerRelay(registry)
        registry.addListener(relay.listener)
        val onMessage: (RegisteredNode, NodeMessage) -> Unit = { n, m -> if (!relay.handle(n, m)) hub.handle(n, m) }
        // clients connect to the master half of this JVM when client.* is configured in fission.properties (client.enabled=false turns it off)
        var csRef: net.fissionmc.master.client.ClientServer? = null
        closeables += net.fissionmc.master.MasterStatsPublisher(registry, relay, { csRef?.onlinePlayers ?: 0 })
        val clientCfg = net.fissionmc.master.client.ClientConfig.load(Path.of("fission.properties").toAbsolutePath())
        if (clientCfg.enabled && java.nio.file.Files.readAllLines(Path.of("fission.properties").toAbsolutePath()).any { it.trim().startsWith("client.listen") }) {
            val cs = net.fissionmc.master.client.ClientServer(clientCfg, relay = relay, log = { log.info(it) }).start()
            csRef = cs
            closeables += cs
            registry.addListener(object : NodeRegistry.Listener { override fun joined(node: RegisteredNode) { cs.attachWaiting() } })
        }
        val local = NodeAddress.Local("fission-master")
        val localT = NettyTransport(NodeSupport.masterNode(MC_VERSION))
        closeables += localT
        val m = MasterServer(localT, local, cfg.worldRoot, registry, onMessage).start()
        closeables += m
        master = m
        cfg.listen?.let { tcp ->
            val tcpT = NettyTransport(NodeSupport.masterNode(MC_VERSION), security = NodeSupport.masterSecurity(cfg))
            closeables += tcpT
            closeables += MasterServer(tcpT, tcp, cfg.worldRoot, registry, onMessage).start()
            log.info("Master also listening for remote backends on ${tcp.host}:${tcp.port}")
        }
        log.info("Master hosted in this JVM, serving worlds from ${cfg.worldRoot}")
        return local
    }

    @JvmStatic
    fun stop() {
        val c = closeables; closeables = mutableListOf()
        c.asReversed().forEach { runCatching { it.close() } }
        link = null
        started = false
    }
}
