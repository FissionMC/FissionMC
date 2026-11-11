package net.fissionmc.master

import net.fissionmc.net.*
import net.fissionmc.protocol.MC_VERSION
import net.fissionmc.master.client.ClientConfig
import net.fissionmc.master.client.ClientServer
import java.nio.file.Path

/** Standalone master process: `fission.yml` with node.mode master, node.listen, node.authorized, node.world-root (every key: fission.default.yml). */
fun main(args: Array<String>) {
    // any failure while starting must END the process: a dead main thread with live Netty threads is a zombie that ignores the panel's stop
    try { runMaster(args) } catch (t: Throwable) {
        println("[master] STARTUP ERROR, master not started: $t"); t.printStackTrace(); kotlin.system.exitProcess(2)
    }
}

private fun runMaster(args: Array<String>) {
    // "nogui" / "--nogui" are accepted and ignored so a Minecraft-style launcher line (java -jar server.jar nogui, e.g. a Pterodactyl egg) works; the first other argument is the config file
    val configFile = Path.of(args.firstOrNull { it != "nogui" && !it.startsWith("-") } ?: "fission.yml").toAbsolutePath()
    Banner.print("master", listOf("config ${configFile}", "console: stop, list, players, admin, send, trace, help"))
    LogFormat.install()
    ShutdownWatchdog.install("master", 30)
    // the console reads from the very start, so `stop` works even while the master is still starting (or stuck binding); the full commands replace this handler at the end
    val commands = java.util.concurrent.atomic.AtomicReference<(String) -> Unit> { line ->
        if (line.trim().lowercase().substringBefore(' ') in setOf("stop", "end", "exit", "quit")) { println("[master] stopping"); kotlin.system.exitProcess(0) }
        else if (line.isNotBlank()) println("[master] still starting: only stop works yet")
    }
    Thread({
        val console = java.io.BufferedReader(java.io.InputStreamReader(System.`in`))
        while (true) { val line = console.readLine() ?: break; try { commands.get()(line) } catch (e: Exception) { println("[master] console command failed: $e") } }
    }, "console").also { it.isDaemon = true }.start()
    if (FissionConfig.writeDefaultIfMissing(configFile, MasterMainResources.defaultConfig())) println("[master] no ${configFile.fileName}: wrote the commented default to $configFile")
    // every section is read up front; an unknown key, a wrong type or an invalid region map stops the master with the key path
    val mc = try { MasterConfig.read(FissionConfig.load(configFile), configFile.parent, configFile.fileName.toString()) { println("[master] $it") } } catch (e: IllegalArgumentException) {
        println("[master] CONFIG ERROR, master not started: ${e.message}"); System.err.println("[master] CONFIG ERROR, master not started: ${e.message}"); kotlin.system.exitProcess(2)
    }
    // flight recorder + debug flag (both live: see the config reload below)
    FissionDebug.fromConfig(mc.debug); net.fissionmc.net.trace.Trace.init("master", configFile.parent.resolve("trace")); net.fissionmc.net.trace.Trace.apply(mc.trace)
    if (net.fissionmc.net.trace.Trace.on) println("[master] ${net.fissionmc.net.trace.Trace.status()}")
    val cfg = mc.node; val regions = mc.regions; val clientCfg = mc.client; val settings = mc.settings; val ghostZone = mc.ghostZone; val stripChunks = mc.borderStrip
    val chunkAuthorityOn = mc.chunkAuthority; val chunkDeterministic = mc.chunkDeterministic; val leaseSeconds = mc.leaseSeconds; val statsSeconds = mc.chunkStatsSeconds
    val chunkStoreDir = mc.chunkStoreDir; val playerDataDir = mc.playerDataDir
    val dbgPackets = mc.debugPackets; val dbgDump = mc.debugDumpClientStream; val dbgRelayProbe = mc.debugRelayProbe; val dbgRecordLimbo = mc.debugRecordLimbo
    val listen = cfg.listen!!
    val loopbackListen = listen.host == "127.0.0.1" || listen.host == "localhost" || listen.host == "::1"
    if (!cfg.security && !loopbackListen && !cfg.allowInsecure)
        fail("node.security is false and node.listen is ${listen.host}:${listen.port}: anyone who reaches that port could connect as a backend (read player data, grant operator, push chunks). Turn security on, listen on 127.0.0.1, or set node.allow-insecure: true if this network is closed")
    val transport = NettyTransport(NodeSupport.masterNode(MC_VERSION), security = NodeSupport.masterSecurity(cfg))
    val registry = NodeRegistry()
    val hub = ReplicationHub.persistent(configFile.resolveSibling("replication.state"), java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "replication-state").also { it.isDaemon = true } }) { println(it) }
    registry.addListener(hub.listener)
    val relay = PlayerRelay(registry)
    relay.playerData = PlayerDataStore(Path.of(playerDataDir), { println(it) })
    registry.addListener(relay.listener)
    relay.regions = regions
    if (regions.enabled) { println("[master] regions on:"); regions.describe().forEach { println("[master]   $it") }; registry.addListener(object : NodeRegistry.Listener { override fun joined(node: RegisteredNode) { node.link.send(net.fissionmc.protocol.RegionMap(node.info.nodeId, regions.toRules())) } }) ; java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "regions-resend").also { it.isDaemon = true } }.scheduleWithFixedDelay({ registry.all.filter { it.link.isOpen }.forEach { n -> n.link.send(net.fissionmc.protocol.RegionMap(n.info.nodeId, regions.toRules())) } }, 5, 5, java.util.concurrent.TimeUnit.SECONDS) }
    // chunk authority: the master is the canonical store of chunk data
    val chunks = if (chunkAuthorityOn) {
        val det = chunkDeterministic
        val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "chunk-authority").also { it.isDaemon = true } }
        val store = net.fissionmc.master.chunk.FileChunkStore(Path.of(chunkStoreDir)) { println(it) }
        // chunk requests and pushes read and write files: on a thread of their own, never on the network threads that relay the players' packets
        val chunkIo = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "chunk-store").also { it.isDaemon = true } }
        net.fissionmc.master.chunk.ChunkAuthority(store, registry, timer, { println(it) }, leaseSeconds * 1000, deterministic = { d -> "true" in det || d in det }, generationOwner = { d, x, z -> regions.generationOwner(d, x, z, stripChunks) }, ownerGenerates = { n, d, x, z -> relay.playerNear(n, d, x, z, ghostZone) }, regionOwner = { d, x, z -> if (regions.enabled) regions.owner(d, x * 16 + 8, z * 16 + 8) else null }, executor = chunkIo).also { a ->
            val every = statsSeconds
            var last = ""
            timer.scheduleWithFixedDelay({ val l = a.statsLine(); if (l != last) { last = l; println(l) } }, every, every, java.util.concurrent.TimeUnit.SECONDS)
            registry.addListener(a.listener)
            println("[master] chunk authority on")
            chunkIo.execute { println("[master] chunk store: ${store.count()} blobs") }   // counting walks the whole store: not on the start path
        }
    } else null
    // authority off: answer every request with "nothing, generate locally" so backends never wait for a timeout; pushes are ignored
    val chunkOff: ((RegisteredNode, net.fissionmc.protocol.NodeMessage) -> Boolean)? = if (chunks == null) { n, m ->
        if (m is net.fissionmc.protocol.ChunkRequest) { n.link.send(net.fissionmc.protocol.ChunkAbsent(m.reqId, false)); true } else m is net.fissionmc.protocol.ChunkPush
    } else null
    val pluginServer = net.fissionmc.net.plugins.PluginServer(configFile.parent.toAbsolutePath().resolve(mc.plugins.dir).normalize(), mc.plugins.replicate) { println("[master] $it") }
    val master = MasterServer(transport, listen, cfg.worldRoot, registry, { n, m -> if (!relay.handle(n, m) && chunks?.handle(n, m) != true && chunkOff?.invoke(n, m) != true) hub.handle(n, m) }, pluginServer)
    // one allocated port for everything (hosting panels): node.listen == client.listen makes the node link share the players' listener
    val shared = clientCfg.enabled && listen.host == clientCfg.host && listen.port == clientCfg.port
    if (!shared && clientCfg.enabled && listen.port == clientCfg.port)
        fail("node.listen (${listen.host}:${listen.port}) and client.listen (${clientCfg.host}:${clientCfg.port}) use the same port with different hosts: write the SAME value in both to share the port, or use two ports")
    try { master.start(listen = !shared) } catch (e: Exception) { fail("node.listen ${listen.host}:${listen.port} cannot be bound: ${e.message}") }
    if (shared) println("[master] node links and players share ${listen.host}:${listen.port} (a connection starting with byte 0 is a node link, anything else a Minecraft client)")
    master.registry.addListener(object : NodeRegistry.Listener {
        override fun joined(node: RegisteredNode) = println("[master] ${node.info.nodeId} joined")
        override fun left(node: RegisteredNode, reason: String) = println("[master] ${node.info.nodeId} left: $reason")
    })
    println("[master] event log epoch ${hub.epoch}")
    println("[master] plugin replication ${if (mc.plugins.replicate) "on, jars from ${configFile.parent.toAbsolutePath().resolve(mc.plugins.dir).normalize()}" else "off"}")
    println("[master] listening on ${listen.host}:${listen.port}, worlds in ${cfg.worldRoot}")
    if (listen.host == "127.0.0.1" || listen.host == "localhost" || listen.host == "::1")
        println("[master] WARN node.listen is ${listen.host}:${listen.port}: backends in other containers/machines cannot connect (use 0.0.0.0:${listen.port}); a changed value applies live")
    println("[master] node link security: ${if (cfg.security) "on (Ed25519, authorized keys: ${cfg.authorizedFile})" else "OFF (node.security=false: any backend may connect)"}")
    if (settings.permissions.any && !clientCfg.onlineMode)
        println("[master] WARN permissions exist and client.online-mode=false: offline UUIDs derive from the name, so anyone can log in as a listed name (and is then bound to it first if its owner has not logged in yet). Use online mode outside development.")
    // local chat format (chat.local.format): pushed to the backends on join, on every attach and every 5 s (a first send can precede the backend's handlers)
    val chatFormat = net.fissionmc.protocol.ChatFormat(settings.chat.syntax, settings.chat.localFormat)
    relay.chatFormat = chatFormat
    registry.addListener(object : NodeRegistry.Listener { override fun joined(node: RegisteredNode) { node.link.send(chatFormat) } })
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "chat-format-resend").also { it.isDaemon = true } }
        .scheduleWithFixedDelay({ registry.all.filter { it.link.isOpen }.forEach { n -> n.link.send(chatFormat) } }, 5, 5, java.util.concurrent.TimeUnit.SECONDS)
    // master-owned world settings (clocks, weather, game rules, difficulty, border, spawn):
    if (mc.worldSettings.enabled) {
        val ws = net.fissionmc.master.worldsettings.WorldSettingsHub(registry, net.fissionmc.master.worldsettings.WorldSettingsModel(mc.worldSettings), configFile.resolveSibling("world-settings.txt")) { println(it) }
        relay.worldSettings = ws
        Runtime.getRuntime().addShutdownHook(Thread { ws.close() })
    }
    val clients = if (clientCfg.enabled) try { ClientServer(clientCfg, relay = relay, settings = settings, nodeAdopt = if (shared) master::adopt else null).start() } catch (e: Exception) { fail("client.listen ${clientCfg.host}:${clientCfg.port} cannot be bound: ${e.message}" + if (e is java.net.BindException || e.cause is java.net.BindException) " (is another process or listener using this port? node.listen and client.listen may use the same port only when the value is identical)" else "") } else null
    registry.addListener(object : NodeRegistry.Listener { override fun joined(node: RegisteredNode) { clients?.attachWaiting() } })
    val stats = MasterStatsPublisher(registry, relay, { clients?.onlinePlayers ?: 0 })
    dbgDump?.let { System.setProperty("fission.dump-client-stream", it) }
    if (dbgPackets) System.setProperty("fission.debug.packets", "1")
    if (dbgRelayProbe) registry.addListener(RelayProbe(relay, dbgRecordLimbo?.let { Path.of(it) }))
    Runtime.getRuntime().addShutdownHook(Thread { chunks?.let { println(it.statsLine()) }; stats.close(); clients?.close(); master.close(); transport.close() })
    // config reload: a changed fission.yml is read again; listen addresses and the client motd/max players/online mode/compression apply live, everything else after a restart
    val reloadable = setOf("client.listen", "client.motd", "client.max-players", "client.online-mode", "client.compression-threshold", "node.listen")
    ConfigWatcher(configFile, { println("[master] $it") }) { oldText, newText ->
        val changed = ConfigWatcher.changedKeys(oldText, newText)
        println("[master] ${configFile.fileName} changed (${changed.joinToString().ifEmpty { "comments only" }}): reloading")
        val fresh = try { MasterConfig.read(FissionConfig.load(configFile), configFile.parent, configFile.fileName.toString()) { println("[master] $it") } }
            catch (e: Exception) { println("[master] CONFIG RELOAD FAILED, keeping the running configuration: ${e.message}"); return@ConfigWatcher }
        val applied = mutableListOf<String>()
        val sharedNow = fresh.client.enabled && fresh.node.listen?.let { it.host == fresh.client.host && it.port == fresh.client.port } == true
        if (sharedNow != shared && ("node.listen" in changed || "client.listen" in changed)) {
            println("[master] port sharing would change (node.listen == client.listen: $shared -> $sharedNow): restart required for node.listen/client.listen")
            return@ConfigWatcher
        }
        if ("node.listen" in changed && !shared) {
            val to = fresh.node.listen
            if (to == null) println("[master] node.listen was removed: restart required") else master.rebind(to)?.let { println("[master] node.listen: cannot listen on ${to.host}:${to.port}: $it (still on ${(master.address as? NodeAddress.Tcp)?.let { a -> "${a.host}:${a.port}" }})") }
                ?: run { println("[master] backends now connect to ${to.host}:${to.port}"); applied += "node.listen" }
        }
        if (changed.any { it.startsWith("client.") && it in reloadable } && clients != null) {
            clients.rebind(fresh.client)?.let { println("[master] client listener: cannot apply ${fresh.client.host}:${fresh.client.port}: $it (still on ${clients.config.host}:${clients.config.port})") }
                ?: run { applied += changed.filter { it.startsWith("client.") && it in reloadable } }
        }
        val liveDiag = changed.filter { it == "debug" || it == "debug.enabled" || it.startsWith("trace.") }
        if (liveDiag.isNotEmpty()) {
            FissionDebug.fromConfig(fresh.debug); net.fissionmc.net.trace.Trace.apply(fresh.trace)
            println("[master] debug is ${if (FissionDebug.on) "on" else "off"}; ${net.fissionmc.net.trace.Trace.status()}"); applied += liveDiag
        }
        // permissions: (and the old admin:) apply live: the new store replaces the old one and every online player's backend is told
        val livePerm = changed.filter { it == "permissions" || it.startsWith("permissions.") || it == "admin" || it.startsWith("admin.") }
        if (livePerm.isNotEmpty() && clients != null) { clients.reloadPermissions(fresh.settings.permissions); applied += livePerm }
        val later = changed.filter { it !in reloadable && it !in liveDiag && (clients == null || it !in livePerm) }
        if (applied.isNotEmpty()) println("[master] applied live: ${applied.joinToString()}")
        if (later.isNotEmpty()) println("[master] needs a restart to take effect: ${later.joinToString()}")
    }.start()

    // the console: a hosting panel (Pterodactyl) sends "stop" to stdin and marks the server running when this line matches `)! For help, type "`
    val startedAt = java.lang.management.ManagementFactory.getRuntimeMXBean().startTime
    println("[master] Done (${"%.3f".format((System.currentTimeMillis() - startedAt) / 1000.0)}s)! For help, type \"help\"")
    commands.set { line ->
        when (line.trim().lowercase().substringBefore(' ')) {
            "" -> {}
            "stop", "end", "exit", "quit" -> { println("[master] stopping"); kotlin.system.exitProcess(0) }
            "list" -> println("[master] ${registry.all.size} backends: ${registry.all.joinToString { it.info.nodeId }.ifEmpty { "-" }}; ${clients?.onlinePlayers ?: 0} players")
            "help", "?" -> println("[master] commands: stop, list, players, admin list|add|remove <player|uuid>, send <player> <backend>, trace [start|stop|dump [seconds]|mark <text>], help")
            else -> if (clients == null || !net.fissionmc.master.client.MasterCommands.console(clients, line) { println("[master] $it") }) println("[master] unknown command '${line.trim()}', type help")
        }
    }
    Thread.currentThread().join()
}

/** A startup error the operator can act on: one clear line on stdout and stderr, exit status 2 (no stack trace). */
private fun fail(msg: String): Nothing { println("[master] STARTUP ERROR, master not started: $msg"); System.err.println("[master] STARTUP ERROR, master not started: $msg"); kotlin.system.exitProcess(2) }

/** The commented default `fission.yml` the master writes when the file is missing (`master/src/main/resources/fission.default.yml`). */
object MasterMainResources {
    fun defaultConfig(): String = MasterMainResources::class.java.getResourceAsStream("/fission.default.yml")!!.readBytes().toString(Charsets.UTF_8)
}
