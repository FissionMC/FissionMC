package net.fissionmc.master

import net.fissionmc.net.*
import net.fissionmc.protocol.MC_VERSION
import net.fissionmc.master.client.ClientConfig
import net.fissionmc.master.client.ClientServer
import java.nio.file.Path

/** Standalone master process: `fission.yml` with node.mode master, node.listen, node.authorized, node.world-root (every key: fission.default.yml). */
fun main(args: Array<String>) {
    val configFile = Path.of(args.firstOrNull() ?: "fission.yml").toAbsolutePath()
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
    val master = MasterServer(transport, listen, cfg.worldRoot, registry) { n, m -> if (!relay.handle(n, m) && chunks?.handle(n, m) != true && chunkOff?.invoke(n, m) != true) hub.handle(n, m) }.start()
    master.registry.addListener(object : NodeRegistry.Listener {
        override fun joined(node: RegisteredNode) = println("[master] ${node.info.nodeId} joined")
        override fun left(node: RegisteredNode, reason: String) = println("[master] ${node.info.nodeId} left: $reason")
    })
    println("[master] event log epoch ${hub.epoch}")
    println("[master] listening on ${listen.host}:${listen.port}, worlds in ${cfg.worldRoot}")
    if (settings.adminStore.any && !clientCfg.onlineMode)
        println("[master] WARN admins exist and client.online-mode=false: offline UUIDs derive from the name, so anyone can log in as an admin's name (and is then bound to it first if the admin has not logged in yet). Use online mode outside development.")
    // local chat format (chat.local.format): pushed to the backends on join, on every attach and every 5 s (a first send can precede the backend's handlers)
    val chatFormat = net.fissionmc.protocol.ChatFormat(settings.chat.syntax, settings.chat.localFormat)
    relay.chatFormat = chatFormat
    registry.addListener(object : NodeRegistry.Listener { override fun joined(node: RegisteredNode) { node.link.send(chatFormat) } })
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "chat-format-resend").also { it.isDaemon = true } }
        .scheduleWithFixedDelay({ registry.all.filter { it.link.isOpen }.forEach { n -> n.link.send(chatFormat) } }, 5, 5, java.util.concurrent.TimeUnit.SECONDS)
    val clients = if (clientCfg.enabled) ClientServer(clientCfg, relay = relay, settings = settings).start() else null
    registry.addListener(object : NodeRegistry.Listener { override fun joined(node: RegisteredNode) { clients?.attachWaiting() } })
    val stats = MasterStatsPublisher(registry, relay, { clients?.onlinePlayers ?: 0 })
    dbgDump?.let { System.setProperty("fission.dump-client-stream", it) }
    if (dbgPackets) System.setProperty("fission.debug.packets", "1")
    if (dbgRelayProbe) registry.addListener(RelayProbe(relay, dbgRecordLimbo?.let { Path.of(it) }))
    Runtime.getRuntime().addShutdownHook(Thread { chunks?.let { println(it.statsLine()) }; stats.close(); clients?.close(); master.close(); transport.close() })
    Thread.currentThread().join()
}

/** The commented default `fission.yml` the master writes when the file is missing (`master/src/main/resources/fission.default.yml`). */
object MasterMainResources {
    fun defaultConfig(): String = MasterMainResources::class.java.getResourceAsStream("/fission.default.yml")!!.readBytes().toString(Charsets.UTF_8)
}
