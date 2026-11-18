package net.fissionmc.master.client

import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.google.gson.JsonObject
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioServerSocketChannel
import net.fissionmc.packets.FissionPacketEventsAPI
import net.fissionmc.packets.Side
import net.fissionmc.protocol.MC_VERSION
import java.io.Closeable
import java.net.InetSocketAddress
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The master's client-facing Netty server (decision D1: clients connect only here).
 * Event loops handle I/O only; Mojang authentication runs through [authenticator] (async HTTP), and limbo timers
 * run on the channel's own loop, so nothing blocks a loop.
 */
class ClientServer(
    initialConfig: ClientConfig,
    val authenticator: Authenticator = MojangAuthenticator(),
    /** Players currently attached to backends (for the status ping); limbo players are added by this class. */
    private val extraPlayers: () -> Int = { 0 },
    val relay: net.fissionmc.master.PlayerRelay? = null,
    val log: (String) -> Unit = { println(it) },
    settings: MasterSettings = MasterSettings(),
    /** Set = the node link shares this port: a connection whose first byte is 0 (a node link frame; a Minecraft handshake starts with its non-zero length) goes here. */
    private val nodeAdopt: ((Channel) -> Unit)? = null,
) : Closeable {
    /** The live settings: motd, max players, online mode, compression and the listen address are read from here (replaced by [rebind] on a config reload). */
    @Volatile var config: ClientConfig = initialConfig
        private set
    /** The master's settings. Only `permissions` is replaced at runtime ([reloadPermissions]); everything else is fixed at start. */
    @Volatile var settings: MasterSettings = settings
        private set

    /** fission.yml changed: the new permission groups and players apply at once, every online player's backend is told. */
    fun reloadPermissions(fresh: PermissionStore) { settings = settings.copy(permissions = fresh); allSessions().forEach { it.pushPermission() } }

    // ---- connection throttle (client.connection-throttle): new connections per address per 10 s
    private val recent = ConcurrentHashMap<String, LongArray>()
    private fun throttled(ch: Channel): Boolean {
        val limit = config.throttle
        val a = (ch.remoteAddress() as? InetSocketAddress)?.address ?: return false
        if (limit <= 0 || a.isLoopbackAddress) return false
        val now = System.currentTimeMillis()
        if (recent.size > 20_000) recent.entries.removeIf { now - it.value[0] > 10_000 }
        val w = recent.compute(a.hostAddress) { _, v -> if (v == null || now - v[0] > 10_000) longArrayOf(now, 1) else { v[1]++; v } }!!
        if (w[1] == limit + 1L) log("[master] ${a.hostAddress} opened more than $limit connections in 10 s: further ones are refused until it slows down (client.connection-throttle)")
        return w[1] > limit
    }
    /** Global chat (needs the relay). */
    init { relay?.localAnnouncements = settings.announcements.local; relay?.petRadius = settings.petRadius }
    init { relay?.grantedLevel = { p -> this.settings.permissions.levelOf(p.uuid) }; relay?.grantedNodes = { p -> this.settings.permissions.nodesOf(p.uuid).toList() } }   // this.settings: the live property, not the constructor value
    /** [log] that also records every line in the flight recorder (the decisions the master logs: switches, regions, cross commands, tab list, ghosts). */
    val tlog: (String) -> Unit = { s -> net.fissionmc.net.trace.Trace.emit("log") { str("line", s) }; log(s) }
    /** Global chat (needs the relay). */
    val chat: GlobalChatHub? = relay?.let { r -> GlobalChatHub(this, r, tlog).also { h -> r.chatResultHandler = h::onResult } }
    /** The network wide tab list and the ghost players (see `.dev/docs/tablist-ghosts-commands.md`). */
    val tabs = TabListHub(tlog)
    val ghosts = net.fissionmc.master.seamless.GhostHub(settings.ghosts.range.toDouble(), { p -> tabs.entry(p) }, tlog, settings.ghosts.entityExpirySeconds * 1000L)
    val crossCommands = CrossCommands(settings.cross)
    init {
        if (settings.ghosts.enabled) {
            tabs.onShown = ghosts::tabShown
            ghosts.pickupAsk = { node, entity, player, playerNode -> relay?.notePickup(node, player, playerNode); relay?.sendToNode(node, net.fissionmc.protocol.ItemPickup(node, entity.mostSignificantBits, entity.leastSignificantBits, player.mostSignificantBits, player.leastSignificantBits, playerNode)) }
            relay?.ghostHandler = { m -> when (m) { is net.fissionmc.protocol.GhostPose -> ghosts.pose(m); is net.fissionmc.protocol.GhostMeta -> ghosts.meta(m); is net.fissionmc.protocol.GhostSwing -> ghosts.swing(m); is net.fissionmc.protocol.EntityGhostBatch -> ghosts.entityBatch(m); is net.fissionmc.protocol.EntityGhostLinks -> ghosts.entityLinks(m); else -> {} } }
        }
    }
    private val dumpCounter = java.util.concurrent.atomic.AtomicInteger()
    val keyPair: KeyPair = LoginCrypto.newKeyPair()
    private val sessions = ConcurrentHashMap<UUID, ClientSession>()
    private val group = MultiThreadIoEventLoopGroup(0, NioIoHandler.newFactory())
    private var bound: Channel? = null
    val port: Int get() = (bound?.localAddress() as? InetSocketAddress)?.port ?: config.port
    val onlinePlayers: Int get() = sessions.size + extraPlayers()

    fun start(): ClientServer {
        FissionPacketEventsAPI.install(Side.MASTER)
        bound = bindListener(config.host, config.port)
        log("[master] clients: ${config.host}:$port, online-mode=${config.onlineMode}, compression=${config.compressionThreshold}" + if (nodeAdopt != null) ", node links share this port" else "")
        return this
    }

    private fun addPlayerHandlers(ch: Channel) {
        System.getProperty("fission.dump-client-stream")?.let { d -> ch.pipeline().addLast("dump", StreamDump(java.nio.file.Files.createDirectories(java.nio.file.Path.of(d)), dumpCounter.incrementAndGet())) }
        ch.pipeline().addLast("splitter", FrameDecoder()).addLast("prepender", FramePrepender())
            .addLast("session", ClientSession(this@ClientServer))
    }

    /** Looks at the first byte of a connection on a shared port, hands the channel to the node link or to the player pipeline and gets out of the way (its buffered bytes are forwarded). */
    private class ProtocolSniffer(val node: (Channel) -> Unit, val players: (Channel) -> Unit) : io.netty.handler.codec.ByteToMessageDecoder() {
        /** A connection that never sends a byte has no other timeout (the session's idle task starts after the hand-over): drop it. */
        override fun handlerAdded(ctx: io.netty.channel.ChannelHandlerContext) {
            super.handlerAdded(ctx)
            ctx.executor().schedule({ if (!ctx.isRemoved && ctx.channel().isActive) ctx.close() }, 10, java.util.concurrent.TimeUnit.SECONDS)
        }
        override fun decode(ctx: io.netty.channel.ChannelHandlerContext, buf: io.netty.buffer.ByteBuf, out: MutableList<Any>) {
            if (!buf.isReadable) return
            if (buf.getUnsignedByte(buf.readerIndex()).toInt() == 0) node(ctx.channel()) else players(ctx.channel())
            ctx.pipeline().remove(this)
        }
    }

    private fun bindListener(host: String, port: Int): Channel = ServerBootstrap().group(group).channel(NioServerSocketChannel::class.java)
        .childOption(ChannelOption.TCP_NODELAY, true)
        .childHandler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                if (throttled(ch)) { ch.close(); return }
                val adopt = nodeAdopt
                if (adopt == null) addPlayerHandlers(ch)
                else ch.pipeline().addLast("sniffer", ProtocolSniffer(adopt) { c -> addPlayerHandlers(c); c.pipeline().context("prepender").fireChannelActive() })
            }
        }).bind(host, port).sync().channel()

    /**
     * Applies a reloaded config: motd, max players, online mode and compression take effect for the next ping/login; a changed listen address is bound first and the old
     * listener closed afterwards (same port with another host: close first, restore the old one if the bind fails). Players who are connected stay connected.
     * Returns null on success, else why the new address could not be bound (the old listener keeps running then).
     */
    @Synchronized fun rebind(new: ClientConfig): String? {
        if (new.host == config.host && new.port == config.port) { config = new; return null }
        val old = bound; val was = "${config.host}:${config.port}"
        try {
            bound = bindListener(new.host, new.port); config = new; old?.close()
        } catch (first: Throwable) {
            if (new.port != config.port) return first.message ?: first.toString()
            old?.close()
            try { bound = bindListener(new.host, new.port); config = new }
            catch (second: Throwable) { bound = runCatching { bindListener(config.host, config.port) }.getOrNull(); return second.message ?: second.toString() }
        }
        log("[master] clients: now listening on ${new.host}:$port (was $was)")
        return null
    }

    /** An announcement line for everybody who is playing on a backend (limbo players get nothing, like chat). */
    fun announce(line: net.kyori.adventure.text.Component?) { if (line != null) allSessions().forEach { it.tell(line) } }

    /** Sessions that are playing on a backend right now (global chat audience). */
    fun allSessions(): List<ClientSession> = sessions.values.toList()
    fun findByName(name: String): ClientSession? = sessions.values.firstOrNull { it.playerName.equals(name, ignoreCase = true) }

    /** Limbo players wait here and are let in at a limited rate, see [AdmissionQueue]. */
    val admission = AdmissionQueue<ClientSession>(settings.limbo.admitPerSecond, settings.limbo.burst)
    private val admissionTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fission-admission").also { it.isDaemon = true } }
    private var noticeTicks = 0
    init {
        admissionTimer.scheduleWithFixedDelay({
            try {
                val up = relay?.readyNodeIds()?.isNotEmpty() == true   // a backend that has joined the link but still boots cannot log a player in
                admission.admit(up).forEach { it.admitFromQueue() }
                if (++noticeTicks * 250L >= settings.limbo.noticeSeconds * 1000L) { noticeTicks = 0; val starting = !up && relay?.bootingNodeIds()?.isNotEmpty() == true; admission.snapshot().forEachIndexed { i, c -> c.waitingNotice(i + 1, up, starting) } }
            } catch (t: Throwable) { tlog("[limbo] queue failed: $t") }
        }, 250, 250, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** A backend became available: the waiting limbo players are let in by the queue. */
    fun attachWaiting() { sessions.values.forEach { it.tryAttach() } }

    /** A backend became ready: players held back at a border towards it are checked again (a player who stands still sends no new position). */
    fun regionRecheck() { sessions.values.forEach { it.regionRecheck() } }

    /** Registers [s] for its account; returns the session that held the account before (the caller ends it: a second login replaces the first, like vanilla). */
    internal fun register(s: ClientSession, p: PlayerProfile): ClientSession? = sessions.put(p.uuid, s)?.takeIf { it !== s }
    internal fun unregister(s: ClientSession) { sessions.values.remove(s) }

    fun statusJson(): JsonObject = JsonObject().apply {
        add("version", JsonObject().apply { addProperty("name", MC_VERSION); addProperty("protocol", ClientSession.PROTOCOL) })
        add("players", JsonObject().apply { addProperty("max", config.shownMax(onlinePlayers)); addProperty("online", config.shownOnline(onlinePlayers)) })
        add("description", JsonObject().apply { addProperty("text", config.motd) })
    }

    override fun close() { admissionTimer.shutdownNow(); bound?.close(); group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS) }
}
