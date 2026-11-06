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
    val config: ClientConfig,
    val authenticator: Authenticator = MojangAuthenticator(),
    /** Players currently attached to backends (for the status ping); limbo players are added by this class. */
    private val extraPlayers: () -> Int = { 0 },
    val relay: net.fissionmc.master.PlayerRelay? = null,
    val log: (String) -> Unit = { println(it) },
    val settings: MasterSettings = MasterSettings(),
) : Closeable {
    /** Global chat (needs the relay). */
    val chat: GlobalChatHub? = relay?.let { r -> GlobalChatHub(this, r, log).also { h -> r.chatResultHandler = h::onResult } }
    private val dumpCounter = java.util.concurrent.atomic.AtomicInteger()
    val keyPair: KeyPair = LoginCrypto.newKeyPair()
    private val sessions = ConcurrentHashMap<UUID, ClientSession>()
    private val group = MultiThreadIoEventLoopGroup(0, NioIoHandler.newFactory())
    private var bound: Channel? = null
    val port: Int get() = (bound?.localAddress() as? InetSocketAddress)?.port ?: config.port
    val onlinePlayers: Int get() = sessions.size + extraPlayers()

    fun start(): ClientServer {
        FissionPacketEventsAPI.install(Side.MASTER)
        bound = ServerBootstrap().group(group).channel(NioServerSocketChannel::class.java)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    System.getProperty("fission.dump-client-stream")?.let { d -> ch.pipeline().addLast("dump", StreamDump(java.nio.file.Files.createDirectories(java.nio.file.Path.of(d)), dumpCounter.incrementAndGet())) }
                    ch.pipeline().addLast("splitter", FrameDecoder()).addLast("prepender", FramePrepender())
                        .addLast("session", ClientSession(this@ClientServer))
                }
            }).bind(config.host, config.port).sync().channel()
        log("[master] clients: ${config.host}:$port, online-mode=${config.onlineMode}, compression=${config.compressionThreshold}")
        return this
    }

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
