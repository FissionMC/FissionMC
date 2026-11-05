package net.fissionmc.backend.relay

import com.google.common.collect.ArrayListMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import net.fissionmc.backend.node.NodeBootstrap
import net.fissionmc.protocol.*
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.configuration.ConfigurationProtocols
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Logger

/**
 * Backend side of the player relay (D1: the client talks only to the master).
 *
 * For every [PlayerAttach] a virtual client connection is created: vanilla's own [Connection] on an in-process
 * channel whose pipeline is vanilla's serialization WITHOUT splitter/compression/encryption (`configureSerialization(local=true)`),
 * so the bytes crossing it are exactly packet frames (varint id + payload) like [PlayerPacketToBackend]/[PlayerPacketToClient].
 * Login is skipped (profile and UUID come from the master, like Velocity modern forwarding): the connection starts directly in the
 * configuration phase and the normal vanilla/Paper handlers take over. No Mixin is needed: everything used is public API.
 *
 * Threading: node-link messages are queued and handled on the server thread in [tick]; packets the server sends leave
 * through [Out] from whatever thread vanilla sends on (writes to the node link are thread safe).
 * Known gaps: the real client address is not forwarded (loopback is reported), no pre-login events (whitelist/bans/PlayerLoginEvent).
 */
object PlayerRelayBackend {
    private val log = Logger.getLogger("FissionMC")
    @Volatile private var enabled = false
    private val inbox = ConcurrentLinkedQueue<NodeMessage>()
    private val sessions = HashMap<Long, Virtual>()   // server thread only
    /** Packets queued behind a saturated master link before the player is dropped. */
    private const val MAX_UNWRITABLE_FRAMES = 20_000

    val activePlayers: Int get() = synchronized(sessions) { sessions.size }

    private class Virtual(val id: Long, val name: String) { lateinit var conn: Connection; lateinit var channel: EmbeddedChannel; var blocked = 0 }

    /** The channel reports a loopback address (EmbeddedChannel's default address type is not an InetSocketAddress). */
    private class VirtualChannel(init: ChannelInitializer<Channel>) : EmbeddedChannel(init) {
        override fun remoteAddress0(): SocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
    }

    /** First handler in the pipeline: everything the server writes towards the client ends here and is sent to the master. */
    private class Out(val v: Virtual) : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg !is ByteBuf) { ctx.write(msg, promise); return }
            val b = msg
            val bytes = ByteArray(b.readableBytes()); b.readBytes(bytes); b.release()
            val l = NodeBootstrap.link
            if (l != null && l.isOpen) {
                l.send(PlayerPacketToClient(v.id, bytes))
                if (!l.isWritable && ++v.blocked > MAX_UNWRITABLE_FRAMES) { log.warning("[fission] relay to master is saturated, dropping ${v.name}"); ctx.channel().close() }
                else if (l.isWritable) v.blocked = 0
            }
            promise.setSuccess()
        }
    }

    @JvmStatic fun attach() {
        NodeBootstrap.addMessageHandler { m -> if (m is PlayerAttach || m is PlayerPacketToBackend || m is PlayerDetach) inbox.add(m) }
        enabled = true
        log.info("[fission] player relay enabled")
    }

    @JvmStatic fun tick(server: MinecraftServer) {
        if (!enabled) return
        val link = NodeBootstrap.link
        if (link == null || !link.isOpen) { if (sessions.isNotEmpty()) closeAll("master link lost"); inbox.clear(); return }
        while (true) {
            when (val m = inbox.poll() ?: break) {
                is PlayerAttach -> runCatching { create(server, m) }.onFailure {
                    log.warning("[fission] could not attach ${m.name}: $it"); link.send(PlayerDetach(m.playerId, "attach failed: ${it.message}"))
                }
                is PlayerPacketToBackend -> sessions[m.playerId]?.let { v ->
                    // Replies to a closed channel are dropped; a decoding error is handled by vanilla's Connection.exceptionCaught.
                    if (v.channel.isOpen) { v.channel.writeInbound(Unpooled.wrappedBuffer(m.bytes)); v.channel.runPendingTasks() }
                }
                is PlayerDetach -> sessions.remove(m.playerId)?.let { synchronized(sessions) {}; it.channel.close() }
            }
        }
    }

    private fun create(server: MinecraftServer, a: PlayerAttach) {
        val props = ArrayListMultimap.create<String, Property>()
        a.properties.forEach { props.put(it.name, Property(it.name, it.value, it.signature)) }
        val profile = GameProfile(UUID(a.uuidHigh, a.uuidLow), a.name, PropertyMap(props))
        val v = Virtual(a.playerId, a.name)
        val conn = Connection(PacketFlow.SERVERBOUND)
        v.conn = conn
        v.channel = VirtualChannel(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                val p = ch.pipeline()
                Connection.configureSerialization(p, PacketFlow.SERVERBOUND, true, null)
                conn.configurePacketHandler(p)
                p.addFirst("fission_out", Out(v))
            }
        })
        v.channel.closeFuture().addListener {
            val removed = synchronized(sessions) { sessions.remove(v.id) }   // idempotent
            NodeBootstrap.link?.takeIf { it.isOpen }?.send(PlayerDetach(v.id, "closed on backend"))
            if (removed != null) log.info("[fission] relayed player ${v.name} closed")
        }
        synchronized(sessions) { sessions[v.id] = v }
        // like vanilla's login acknowledgement: switch both directions to configuration, register for ticking, start
        conn.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND)
        val listener = ServerConfigurationPacketListenerImpl(server, conn, CommonListenerCookie.createInitial(profile, false))
        conn.setupInboundProtocol(ConfigurationProtocols.SERVERBOUND, listener)
        server.connection.connections.add(conn)
        listener.startConfiguration()
        log.info("[fission] relayed player ${a.name} attached (player ${a.playerId})")
    }

    private fun closeAll(why: String) {
        val all = synchronized(sessions) { sessions.values.toList() }
        all.forEach { it.channel.close() }
        log.info("[fission] closed ${all.size} relayed players: $why")
    }
}
