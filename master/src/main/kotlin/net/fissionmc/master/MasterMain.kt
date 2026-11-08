package net.fissionmc.master

import net.fissionmc.net.*
import net.fissionmc.protocol.MC_VERSION
import net.fissionmc.master.client.ClientConfig
import net.fissionmc.master.client.ClientServer
import java.nio.file.Path

/** Standalone master process: `fission.yml` with node.mode master, node.listen, node.authorized, node.world-root (every key: fission.default.yml). */
fun main(args: Array<String>) {
    // "nogui" / "--nogui" are accepted and ignored so a Minecraft-style launcher line (java -jar server.jar nogui, e.g. a Pterodactyl egg) works; the first other argument is the config file
    val configFile = Path.of(args.firstOrNull { it != "nogui" && !it.startsWith("-") } ?: "fission.yml").toAbsolutePath()
    Banner.print("master", listOf("config ${configFile}", "console: stop, list, help"))
    LogFormat.install()
    if (FissionConfig.writeDefaultIfMissing(configFile, MasterMainResources.defaultConfig())) println("[master] no ${configFile.fileName}: wrote the commented default to $configFile")
    // every section is read up front; an unknown key, a wrong type or an invalid region map stops the master with the key path
    val mc = try { MasterConfig.read(FissionConfig.load(configFile), configFile.parent, configFile.fileName.toString()) { println("[master] $it") } } catch (e: IllegalArgumentException) {
        println("[master] CONFIG ERROR, master not started: ${e.message}"); System.err.println("[master] CONFIG ERROR, master not started: ${e.message}"); kotlin.system.exitProcess(2)
    }
    val cfg = mc.node; val regions = mc.regions; val clientCfg = mc.client; val settings = mc.settings; val ghostZone = mc.ghostZone; val stripChunks = mc.borderStrip
    val chunkAuthorityOn = mc.chunkAuthority; val chunkDeterministic = mc.chunkDeterministic; val leaseSeconds = mc.leaseSeconds; val statsSeconds = mc.chunkStatsSeconds
    val chunkStoreDir = mc.chunkStoreDir; val playerDataDir = mc.playerDataDir
    val dbgPackets = mc.debugPackets; val dbgDump = mc.debugDumpClientStream; val dbgRelayProbe = mc.debugRelayProbe; val dbgRecordLimbo = mc.debugRecordLimbo
    val listen = cfg.listen!!
    val transport = NettyTransport(NodeSupport.masterNode(MC_VERSION), security = NodeSupport.masterSecurity(cfg))
    val registry = NodeRegistry()
    val hub = ReplicationHub()
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
        net.fissionmc.master.chunk.ChunkAuthority(store, registry, timer, { println(it) }, leaseSeconds * 1000, deterministic = { d -> "true" in det || d in det }, generationOwner = { d, x, z -> regions.generationOwner(d, x, z, stripChunks) }, ownerGenerates = { n, d, x, z -> relay.playerNear(n, d, x, z, ghostZone) }).also { a ->
            val every = statsSeconds
            var last = ""
            timer.scheduleWithFixedDelay({ val l = a.statsLine(); if (l != last) { last = l; println(l) } }, every, every, java.util.concurrent.TimeUnit.SECONDS)
            registry.addListener(a.listener)
            println("[master] chunk authority on (${store.count()} blobs in the store)")
        }
    } else null
    // authority off: answer every request with "nothing, generate locally" so backends never wait for a timeout; pushes are ignored
    val chunkOff: ((RegisteredNode, net.fissionmc.protocol.NodeMessage) -> Boolean)? = if (chunks == null) { n, m ->
        if (m is net.fissionmc.protocol.ChunkRequest) { n.link.send(net.fissionmc.protocol.ChunkAbsent(m.reqId, false)); true } else m is net.fissionmc.protocol.ChunkPush
    } else null
    val pluginServer = net.fissionmc.net.plugins.PluginServer(configFile.parent.toAbsolutePath().resolve(mc.plugins.dir).normalize(), mc.plugins.replicate) { println("[master] $it") }
    val master = MasterServer(transport, listen, cfg.worldRoot, registry, { n, m -> if (!relay.handle(n, m) && chunks?.handle(n, m) != true && chunkOff?.invoke(n, m) != true) hub.handle(n, m) }, pluginServer).start()
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
    if (settings.adminStore.any && !clientCfg.onlineMode)
        println("[master] WARN admins exist and client.online-mode=false: offline UUIDs derive from the name, so anyone can log in as an admin's name (and is then bound to it first if the admin has not logged in yet). Use online mode outside development.")
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
    val clients = if (clientCfg.enabled) ClientServer(clientCfg, relay = relay, settings = settings).start() else null
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
        if ("node.listen" in changed) {
            val to = fresh.node.listen
            if (to == null) println("[master] node.listen was removed: restart required") else master.rebind(to)?.let { println("[master] node.listen: cannot listen on ${to.host}:${to.port}: $it (still on ${(master.address as? NodeAddress.Tcp)?.let { a -> "${a.host}:${a.port}" }})") }
                ?: run { println("[master] backends now connect to ${to.host}:${to.port}"); applied += "node.listen" }
        }
        if (changed.any { it.startsWith("client.") && it in reloadable } && clients != null) {
            clients.rebind(fresh.client)?.let { println("[master] client listener: cannot apply ${fresh.client.host}:${fresh.client.port}: $it (still on ${clients.config.host}:${clients.config.port})") }
                ?: run { applied += changed.filter { it.startsWith("client.") && it in reloadable } }
        }
        val later = changed.filter { it !in reloadable }
        if (applied.isNotEmpty()) println("[master] applied live: ${applied.joinToString()}")
        if (later.isNotEmpty()) println("[master] needs a restart to take effect: ${later.joinToString()}")
    }.start()

    // the console: a hosting panel (Pterodactyl) sends "stop" to stdin and marks the server running when this line matches `)! For help, type "`
    val startedAt = java.lang.management.ManagementFactory.getRuntimeMXBean().startTime
    println("[master] Done (${"%.3f".format((System.currentTimeMillis() - startedAt) / 1000.0)}s)! For help, type \"help\"")
    val console = java.io.BufferedReader(java.io.InputStreamReader(System.`in`))
    while (true) {
        val line = console.readLine() ?: break    // stdin closed (background run): keep serving
        when (line.trim().lowercase().substringBefore(' ')) {
            "" -> {}
            "stop", "end", "exit", "quit" -> { println("[master] stopping"); kotlin.system.exitProcess(0) }
            "list" -> println("[master] ${registry.all.size} backends: ${registry.all.joinToString { it.info.nodeId }.ifEmpty { "-" }}; ${clients?.onlinePlayers ?: 0} players")
            "help", "?" -> println("[master] commands: stop, list, help")
            else -> println("[master] unknown command '${line.trim()}', type help")
        }
    }
    Thread.currentThread().join()
}

/** The commented default `fission.yml` the master writes when the file is missing (`master/src/main/resources/fission.default.yml`). */
object MasterMainResources {
    fun defaultConfig(): String = MasterMainResources::class.java.getResourceAsStream("/fission.default.yml")!!.readBytes().toString(Charsets.UTF_8)
}
