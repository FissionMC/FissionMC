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
    private const val SEED_POLL_S = 10L
    private const val SEED_WAIT_MINUTES = 30L

    @Volatile var link: NodeLink? = null; private set
    /** True once a node mode other than standalone is active: only then is there a master to push chunks/player data to (a standalone server must not queue anything for it). */
    @Volatile var networked: Boolean = false; private set
    @Volatile var master: MasterServer? = null; private set
    /** Backend mode without `node.direct-connections`: the Minecraft port is bound on loopback only (players come through the master; a direct login would skip its auth, permissions and player data). */
    @Volatile private var loopbackOnly = false
    @JvmStatic fun bindLoopbackOnly(): Boolean = loopbackOnly
    private val extraHandlers = java.util.concurrent.CopyOnWriteArrayList<(NodeMessage) -> Unit>()
    /** Every registered handler sees the message; one that throws is logged and does not stop the others (or kill the link). */
    private fun dispatch(msg: NodeMessage) { for (h in extraHandlers) try { h(msg) } catch (t: Exception) { log.log(java.util.logging.Level.WARNING, "[fission] a message handler failed on ${msg.javaClass.simpleName}: $t", t) } }
    private var closeables = mutableListOf<AutoCloseable>()

    /** Later subsystems (replication, packet routing) register here to receive master messages. */
    @JvmStatic fun addMessageHandler(h: (NodeMessage) -> Unit) { extraHandlers += h }

    private val connectListeners = java.util.concurrent.CopyOnWriteArrayList<(NodeLink) -> Unit>()

    /** Called with every master link once the world is pulled: the first connection and every reconnect. */
    @JvmStatic fun addConnectListener(h: (NodeLink) -> Unit) { connectListeners += h }

    /** This backend's node id (`node.id`), set once the world is ready. */
    @JvmStatic @Volatile var nodeId: String = ""

    private var combinedClientCfg: net.fissionmc.master.client.ClientConfig? = null

    @Volatile private var started = false
    /** A link the master connection got after the first one but before startup finished (the first died during the world pull): the pull moves to it instead of waiting on the dead one. */
    @Volatile private var freshLink: NodeLink? = null

    /** A new master link after the first one (reconnect): route its messages, then tell the listeners. */
    private fun relinked(l: NodeLink) {
        l.handler = { msg -> dispatch(msg) }   // before the link is published: what the master sends right after Welcome must not hit the empty default handler
        link = l
        connectListeners.forEach { runCatching { it(l) }.onFailure { e -> log.warning("connect listener failed: $e") } }
    }

    @JvmStatic
    fun start(universe: Path, levelName: String, pluginsDir: Path = Path.of("plugins")) {
        val file = Path.of("fission.yml").toAbsolutePath()
        // missing file: the commented default (standalone), written once; unknown keys and wrong types stop the server with the key path
        if (net.fissionmc.net.FissionConfig.writeDefaultIfMissing(file, NodeBootstrap::class.java.getResourceAsStream("/fission.backend.default.yml")!!.readBytes().toString(Charsets.UTF_8))) log.info("No fission.yml: wrote the commented default to $file")
        val root = net.fissionmc.net.FissionConfig.load(file)
        val parsed = NodeConfig.from(root, file.parent)
        if (parsed.mode == NodeMode.COMBINED) combinedClientCfg = net.fissionmc.master.client.ClientConfig.from(root)
        val pluginsCfg = net.fissionmc.net.plugins.PluginsConfig.from(root)
        val debugOn = root.bool("debug", false)
        val traceCfg = net.fissionmc.net.trace.TraceConfig.from(root)
        val limitsCfg = LimitsConfig.from(root)
        root.finish()
        LimitsConfig.apply(limitsCfg)
        net.fissionmc.net.FissionDebug.fromConfig(debugOn)
        // the backend id: node.id by hand wins; otherwise a random one is generated once and saved (net/NodeIds.kt), so it survives restarts
        val cfg = if (parsed.mode == NodeMode.STANDALONE) parsed else {
            val r = net.fissionmc.net.NodeIds.resolve(file, parsed.nodeId.ifBlank { null }, persistInYml = parsed.mode != NodeMode.COMBINED)
            log.info("backend id: ${r.id}" + (if (r.generated) " (generated, saved to ${r.savedTo})" else if (parsed.nodeId.isBlank()) " (generated earlier, from .fission-node-id)" else " (node.id)"))
            nodeId = r.id
            parsed.copy(nodeId = r.id)
        }
        net.fissionmc.net.trace.Trace.init(cfg.nodeId.ifBlank { "standalone" }, file.parent.resolve("trace")); net.fissionmc.net.trace.Trace.apply(traceCfg)
        if (net.fissionmc.net.trace.Trace.on) log.info(net.fissionmc.net.trace.Trace.status())
        if (cfg.mode == NodeMode.STANDALONE) return
        networked = true
        loopbackOnly = cfg.mode == NodeMode.BACKEND && !cfg.directConnections
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
        if (cfg.mode == NodeMode.BACKEND) NodeSupport.insecureMasterProblem(cfg)?.let { error(it) }
        // link security: the pinned master key (node.master-public or .fission-master-key) or, with neither, pairing mode: the one-time code is printed to the CONSOLE (never to the log file)
        val security = if (address is NodeAddress.Tcp) NodeSupport.backendSecurity(cfg, announce = { code, expires, fp, renewed -> announcePairing(cfg, code, expires, fp, renewed) }, onPaired = { m, first ->
            log.info("PAIRED with the master: pinned its key, fingerprint ${net.fissionmc.net.pairing.Fingerprint.of(m)}" + (if (first) " (saved to ${cfg.masterKeyFile}). Compare this fingerprint with the one `node add` printed on the master console." else ""))
        }, warn = { log.warning(it) }) else null
        security?.pairingClient?.let { pc ->
            if (pc.wanted()) { pc.session(); log.warning("This backend is not paired with a master yet: paste the pairing code above into the master console (`node add <code>`). The code expires; a new one is printed then.") }
            else log.info("Master key pinned (${pc.pin.fingerprint}); node ${cfg.nodeId} key ${net.fissionmc.net.pairing.Fingerprint.of(security.publicKey!!)}")
            val identity = net.fissionmc.net.pairing.BackendIdentity(cfg.nodeId, cfg.keyFile, security, pc.pin) { log.info(it) }
            extraHandlers += { m -> link?.let { identity.handle(it, m) } }
        }
        BackendNet.install()
        val transport = NettyTransport(
            LocalNode(cfg.nodeId, NodeRole.BACKEND, MC_VERSION),
            security = security,
        )
        closeables += transport

        val ready = java.util.concurrent.CompletableFuture<NodeLink>()
        var client0: NodeClient? = null
        val client = NodeClient(
            transport, address,
            onReady = { l, peer ->
                log.info("Connected to master ${peer.nodeId}")
                if (!ready.complete(l)) { if (started) relinked(l) else freshLink = l }
            },
            onFailure = { log.warning("Master connection failed (dialing ${client0?.address ?: address}): ${it.message}") },
            onDisconnect = { log.warning("Lost connection to master: $it"); runCatching { net.fissionmc.backend.chunk.ChunkAuthorityBackend.linkLost() } },
        ).start()
        client0 = client
        closeables += client
        // config reload: a changed node.master makes the backend drop the link and dial the new master address; everything else in fission.yml applies after a restart
        // (the Minecraft port/ip of a backend is vanilla's server.properties and is not live-reloadable)
        if (cfg.mode == NodeMode.BACKEND || cfg.mode == NodeMode.COMBINED) closeables += net.fissionmc.net.ConfigWatcher(file, { log.warning(it) }) { oldText, newText ->
            val changed = net.fissionmc.net.ConfigWatcher.changedKeys(oldText, newText)
            log.info("fission.yml changed (${changed.joinToString().ifEmpty { "comments only" }})")
            val liveDiag = changed.filter { it == "debug" || it.startsWith("trace.") }
            if (liveDiag.isNotEmpty()) {
                try {
                    val fresh = net.fissionmc.net.FissionConfig.load(file)
                    val d = fresh.bool("debug", false); val t = net.fissionmc.net.trace.TraceConfig.from(fresh)
                    net.fissionmc.net.FissionDebug.fromConfig(d); net.fissionmc.net.trace.Trace.apply(t)
                    log.info("applied live: ${liveDiag.joinToString()}; debug is ${if (net.fissionmc.net.FissionDebug.on) "on" else "off"}; ${net.fissionmc.net.trace.Trace.status()}")
                } catch (e: Exception) { log.warning("fission.yml reload failed, keeping debug/trace as they are: ${e.message}") }
            }
            if ("node.master" in changed && cfg.mode == NodeMode.BACKEND) {
                val to = try { NodeConfig.from(net.fissionmc.net.FissionConfig.load(file), file.parent).master } catch (e: Exception) { log.warning("fission.yml reload failed, keeping node.master as it is: ${e.message}"); return@ConfigWatcher }
                if (to == null) log.warning("node.master was removed: restart required") else { log.info("node.master is now ${to.host}:${to.port}: reconnecting"); client.redirect(to) }
            }
            val later = changed.filter { it != "node.master" && it !in liveDiag }
            if (later.isNotEmpty() && cfg.mode == NodeMode.BACKEND) log.info("needs a restart to take effect: ${later.joinToString()}")
        }.start()
        // a backend that starts before its master waits for it (a hosting panel may start them in any order) instead of exiting after a few seconds
        // (waiting for the admin to paste the pairing code is not a connect timeout: the backend waits for ever then, the code renews itself)
        val waitUntil = if (cfg.connectTimeoutSeconds == 0L) Long.MAX_VALUE else System.nanoTime() + cfg.connectTimeoutSeconds * 1_000_000_000L
        var l0: NodeLink? = null; var waited = 0
        while (l0 == null) {
            try { l0 = ready.get(10, TimeUnit.SECONDS) }
            catch (e: java.util.concurrent.TimeoutException) {
                waited += 10
                if (System.nanoTime() > waitUntil && security?.pairingClient?.wanted() != true) { stop(); error("Could not reach the master at ${client.address} within ${cfg.connectTimeoutSeconds}s (node.connect-timeout)") }
                log.info("Still waiting for the master at ${client.address} (${waited}s; node.connect-timeout is ${if (cfg.connectTimeoutSeconds == 0L) "off" else "${cfg.connectTimeoutSeconds}s"})")
            }
        }
        var l: NodeLink = l0
        link = l

        log.info("Pulling world '$levelName' from the master into $dest")
        var puller = WorldPuller(l, dest)
        val seededSignal = java.util.concurrent.Semaphore(0)   // the master says a world now exists (the seeder finished): wakes a waiting backend
        l.handler = { msg -> if (msg is net.fissionmc.protocol.WorldSeeded) seededSignal.release() else if (!puller.handle(msg)) dispatch(msg) }
        val t0 = System.nanoTime()
        val giveUpAt = System.nanoTime() + SEED_WAIT_MINUTES * 60_000_000_000L
        var ready0: net.fissionmc.net.world.PullResult? = null
        while (true) {
            try {
                val pulling = puller.pull(levelName); val pullEnd = System.nanoTime() + PULL_TIMEOUT_S * 1_000_000_000L
                var relink = false
                while (true) {
                    try { ready0 = pulling.get(2, TimeUnit.SECONDS); break }
                    catch (te: java.util.concurrent.TimeoutException) {
                        val f = freshLink
                        if (!l.isOpen && f != null && f !== l && f.isOpen) { relink = true; break }   // the link died during the pull and the master is back: ask again on the new one
                        if (System.nanoTime() > pullEnd) throw te
                    }
                }
                if (relink) {
                    val f = freshLink!!; freshLink = null
                    log.warning("The master link was lost during the world pull: starting it again on the new link")
                    l = f; link = f; puller = WorldPuller(f, dest)
                    f.handler = { msg -> if (msg is net.fissionmc.protocol.WorldSeeded) seededSignal.release() else if (!puller.handle(msg)) dispatch(msg) }
                    continue
                }
                break
            }
            catch (e: java.util.concurrent.ExecutionException) {
                val missing = e.cause as? net.fissionmc.net.world.WorldMissingException
                if (missing == null) { stop(); throw IllegalStateException("World pull failed: ${e.cause?.message ?: e.message}", e) }
                if (missing.seeder == cfg.nodeId) {
                    log.info("The master has no world '$levelName' yet and this backend (${cfg.nodeId}) is the first alphabetically: it creates the world and uploads level.dat, data/ and the other non-chunk files once it is up")
                    WorldSeedUploader.arm(dest, levelName); extraHandlers += { m -> if (m is net.fissionmc.protocol.WorldSeeded) WorldSeedUploader.onSeeded(m) }
                    break
                }
                log.info("The master has no world '$levelName' yet: waiting for ${missing.seeder} to create it (asking again every ${SEED_POLL_S}s, giving up after $SEED_WAIT_MINUTES minutes)")
                if (System.nanoTime() > giveUpAt) { stop(); error("The master still has no world '$levelName' after $SEED_WAIT_MINUTES minutes: start ${missing.seeder} (the seeder), or put a world folder '$levelName' into the master's world-root") }
                seededSignal.tryAcquire(SEED_POLL_S, TimeUnit.SECONDS)
                puller = WorldPuller(l, dest)
            }
            catch (e: Exception) { stop(); throw IllegalStateException("World pull failed: ${e.cause?.message ?: e.message}", e) }
        }
        ready0?.let { r -> log.info("World ready: ${r.downloaded} downloaded (${r.bytes / 1024} KiB), ${r.unchanged} unchanged, ${r.deleted} removed in ${(System.nanoTime() - t0) / 1_000_000} ms") }
        // plugin replication: the master's jars into the plugins folder, before Paper discovers plugins (combined mode shares the folder with its own master half: nothing to pull)
        if (cfg.mode == NodeMode.BACKEND && pluginsCfg.replicate) PluginReplicationStatus.run(l, pluginsDir.toAbsolutePath().normalize(), pluginsCfg.conflict) { msg -> dispatch(msg) }
        // From here on, messages from the master go to the registered handlers.
        l.handler = { msg -> dispatch(msg) }
        nodeId = cfg.nodeId
        net.fissionmc.backend.replication.Replication.attach(cfg.nodeId)
        net.fissionmc.backend.relay.PlayerRelayBackend.attach()
        started = true
        connectListeners.forEach { it(l) }
    }

    /** Prints a pairing code to the console (not the log) with the steps; the log only learns THAT a code was made. */
    private fun announcePairing(cfg: NodeConfig, code: String, expiresAt: Long, fingerprint: String, renewed: Boolean) {
        val minutes = ((expiresAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
        val out = net.fissionmc.net.pairing.ConsoleOut::secretLine
        out("")
        out("[FissionMC] ${if (renewed) "NEW " else ""}PAIRING CODE for backend ${cfg.nodeId} (key fingerprint $fingerprint), valid $minutes min, works once:")
        out("[FissionMC]   $code")
        out("[FissionMC] On the master console type:  node add <the code above>   (check that the master shows the fingerprint $fingerprint). node.master is ${cfg.master?.host}:${cfg.master?.port}.")
        out("")
        log.info("A pairing code for this backend was printed to the console (it is not written to this log); fingerprint $fingerprint")
    }

    /** Hosts the master in this JVM; returns the in-JVM address the backend should dial. */
    private fun startCombinedMaster(cfg: NodeConfig): NodeAddress {
        val registry = NodeRegistry()
        val hub = ReplicationHub()
        registry.addListener(hub.listener)
        val relay = net.fissionmc.master.PlayerRelay(registry)
        registry.addListener(relay.listener)
        val onMessage: (RegisteredNode, NodeMessage) -> Unit = { n, m -> if (!relay.handle(n, m)) hub.handle(n, m) }
        // clients connect to the master half of this JVM when client.* is configured in fission.yml (client.enabled=false turns it off)
        var csRef: net.fissionmc.master.client.ClientServer? = null
        closeables += net.fissionmc.master.MasterStatsPublisher(registry, relay, { csRef?.onlinePlayers ?: 0 })
        val clientCfg = combinedClientCfg ?: net.fissionmc.master.client.ClientConfig()
        if (clientCfg.enabled && clientCfg.listenGiven) {
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
            NodeSupport.insecureListenerProblem(cfg)?.let { error(it) }
            val tcpT = NettyTransport(NodeSupport.masterNode(MC_VERSION), security = NodeSupport.masterSecurity(cfg), guard = NodeSupport.preAuthGuard(cfg))
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
