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
    val settings: MasterSettings = MasterSettings(),
    /** Set = the node link shares this port: a connection whose first byte is 0 (a node link frame; a Minecraft handshake starts with its non-zero length) goes here. */
    private val nodeAdopt: ((Channel) -> Unit)? = null,
) : Closeable {
    /** The live settings: motd, max players, online mode, compression and the listen address are read from here (replaced by [rebind] on a config reload). */
    @Volatile var config: ClientConfig = initialConfig
        private set
    /** Global chat (needs the relay). */
    init { relay?.localAnnouncements = settings.announcements.local; relay?.petRadius = settings.petRadius }
    init { relay?.grantedLevel = { p -> settings.permissions.levelOf(p.uuid) }; relay?.grantedNodes = { p -> settings.permissions.nodesOf(p.uuid).toList() } }
    /** Global chat (needs the relay). */
    val chat: GlobalChatHub? = relay?.let { r -> GlobalChatHub(this, r, log).also { h -> r.chatResultHandler = h::onResult } }
    /** The network wide tab list and the ghost players (see `.dev/docs/tablist-ghosts-commands.md`). */
    val tabs = TabListHub(log)
    val ghosts = net.fissionmc.master.seamless.GhostHub(settings.ghosts.range.toDouble(), { v, p -> tabs.holds(v, p) }, log)
    val crossCommands = CrossCommands(settings.cross)
    init {
        if (settings.ghosts.enabled) {
            tabs.onShown = ghosts::tabShown
            relay?.ghostHandler = { m -> when (m) { is net.fissionmc.protocol.GhostPose -> ghosts.pose(m); is net.fissionmc.protocol.GhostMeta -> ghosts.meta(m); is net.fissionmc.protocol.GhostSwing -> ghosts.swing(m); else -> {} } }
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

    /** A backend became available: move waiting limbo players over. */
    fun attachWaiting() { sessions.values.forEach { it.tryAttach() } }

    internal fun register(s: ClientSession, p: PlayerProfile): Boolean = sessions.putIfAbsent(p.uuid, s) == null
    internal fun unregister(s: ClientSession) { sessions.values.remove(s) }

    fun statusJson(): JsonObject = JsonObject().apply {
        add("version", JsonObject().apply { addProperty("name", MC_VERSION); addProperty("protocol", ClientSession.PROTOCOL) })
        add("players", JsonObject().apply { addProperty("max", config.maxPlayers); addProperty("online", onlinePlayers) })
        add("description", JsonObject().apply { addProperty("text", config.motd) })
    }

    override fun close() { bound?.close(); group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS) }
}
