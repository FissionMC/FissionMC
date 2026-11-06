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
import net.minecraft.network.HiddenByteBuf
import net.minecraft.network.UnconfiguredPipelineHandler
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
    private val sessions = HashMap<Long, Virtual>()
    private val infos = HashMap<Long, PlayerClientInfo>()          // server thread only
    private val pendingFrames = HashMap<Long, MutableList<ByteArray>>() // frames that arrived while the async pre-login runs
    private class Ready(val a: PlayerAttach, val profile: GameProfile?, val deny: String?, val error: Throwable?) { var info: PlayerClientInfo? = null }
    private val ready = ConcurrentLinkedQueue<Ready>()
    private val loginPool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "fission-relay-login").also { it.isDaemon = true } }
    /** Packets queued behind a saturated master link before the player is dropped. */
    private const val MAX_UNWRITABLE_FRAMES = 20_000

    val activePlayers: Int get() = synchronized(sessions) { sessions.size }

    private class Virtual(val id: Long, val name: String) { lateinit var conn: Connection; lateinit var channel: EmbeddedChannel; var blocked = 0; var tap: net.fissionmc.packets.PacketTap? = null }

    /** First handler in the pipeline: everything the server writes towards the client ends here and is sent to the master. */
    private class Out(val v: Virtual) : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            // vanilla hides frames from netty's byte buf handlers inside HiddenByteBuf (local pipeline): unwrap; pass the pipeline tasks on
            val b = HiddenByteBuf.unpack(msg) as? ByteBuf
            if (b == null) { ctx.write(msg, promise); return }
            val raw = ByteArray(b.readableBytes()); b.readBytes(raw); b.release()
            // packet bus: backend -> client direction (a listener may cancel or modify)
            val bytes = v.tap?.let { synchronized(it) { it.toClient(raw) } } ?: raw.takeIf { v.tap == null }
            if (bytes == null) { promise.setSuccess(); return }
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
        net.fissionmc.craft.builtin.FissionStatus.provider = java.util.function.Supplier { net.fissionmc.backend.node.MasterInfo.lines() }
        NodeBootstrap.addMessageHandler { m ->
            if (m is MasterStats) net.fissionmc.backend.node.MasterInfo.onStats(m)
            if (m is GlobalChat || m is GlobalChatObserved || m is PlayerAttach || m is PlayerDetach) GlobalChatBackend.onMessage(m)
            if (m is ChunkData || m is ChunkAbsent || m is ChunkStored || m is ChunkAvailable) net.fissionmc.backend.chunk.ChunkAuthorityBackend.onMessage(m)
            if (m is PlayerFile || m is EntityTransfer || m is EntityRemove) TravelBackend.onMessage(m, serverRef)
            if (m is AnnouncementMode) LocalAnnouncements.onMessage(m)
            if (m is PlayerPermission || m is PlayerDetach) PermissionBackend.onMessage(m, serverRef)
            if (m is PlayerData || m is PlayerDataFlush || m is PlayerAttach || m is PlayerDetach) PlayerDataBackend.onMessage(m, serverRef)
            if (m is PlayerClientInfo || m is PlayerAttach || m is PlayerPacketToBackend || m is PlayerDetach) { inbox.add(m); wake() }
        }
        enabled = true
        log.info("[fission] player relay enabled")
    }

    @Volatile private var serverRef: MinecraftServer? = null
    private val wakePending = java.util.concurrent.atomic.AtomicBoolean()

    /**
     * Relayed frames used to wait for the next tickServer HEAD (up to 50 ms, 25 ms on average). Vanilla runs queued packet tasks on the server
     * thread between ticks (BlockableEventLoop.execute is drained while the server waits for the next tick), so do the same: hand the drain to
     * the server thread right away, coalescing bursts into one task.
     */
    private fun wake() {
        val s = serverRef ?: return
        if (wakePending.compareAndSet(false, true)) s.execute { wakePending.set(false); drain(s) }
    }

    @JvmStatic fun tick(server: MinecraftServer) {
        serverRef = server
        GlobalChatBackend.setServer(server)
        PlayerDataBackend.tick(server)
        TravelBackend.tick(server)
        net.fissionmc.backend.chunk.ChunkAuthorityBackend.tick(server)
        LimboCheck.once(server)
        GameplayCheck.tick(server)
        drain(server)
    }

    private fun drain(server: MinecraftServer) {
        if (!enabled) return
        val link = NodeBootstrap.link
        if (link == null || !link.isOpen) { if (sessions.isNotEmpty()) closeAll("master link lost"); inbox.clear(); return }
        while (true) {
            when (val m = inbox.poll() ?: break) {
                is PlayerClientInfo -> infos[m.playerId] = m
                is PlayerAttach -> { pendingFrames[m.playerId] = ArrayList(); preLogin(m, infos.remove(m.playerId)) }
                is PlayerPacketToBackend -> {
                    val v = sessions[m.playerId]
                    if (v != null) feed(v, m.bytes) else pendingFrames[m.playerId]?.add(m.bytes)
                }
                is PlayerDetach -> { pendingFrames.remove(m.playerId); infos.remove(m.playerId); sessions.remove(m.playerId)?.channel?.close() }
            }
        }
        // players whose async pre-login finished
        while (true) {
            val r = ready.poll() ?: break
            val frames = pendingFrames.remove(r.a.playerId) ?: continue   // detached meanwhile
            if (r.error != null) { log.warning("[fission] pre-login of ${r.a.name} failed: ${r.error}"); link.send(PlayerDetach(r.a.playerId, "kick:Login failed")); continue }
            if (r.deny != null) { link.send(PlayerDetach(r.a.playerId, "kick:${r.deny}")); continue }
            runCatching { create(server, r.a, r.profile!!, r.info) }.onSuccess { v -> frames.forEach { feed(v, it) } }.onFailure {
                log.warning("[fission] could not attach ${r.a.name}: $it"); link.send(PlayerDetach(r.a.playerId, "attach failed: ${it.message}"))
            }
        }
    }

    /** A client frame for [v] through the packet bus into vanilla's connection (server thread). */
    private fun feed(v: Virtual, bytes: ByteArray) {
        val frame = v.tap?.let { synchronized(it) { it.fromClient(bytes) } } ?: bytes.takeIf { v.tap == null } ?: return
        // Replies to a closed channel are dropped; a decoding error is handled by vanilla's Connection.exceptionCaught.
        if (v.channel.isOpen) { v.channel.runPendingTasks(); v.channel.writeInbound(HiddenByteBuf.pack(Unpooled.wrappedBuffer(frame))); v.channel.runPendingTasks() }
    }

    /**
     * Pre-login like the ported login listener does it: AsyncPlayerPreLoginEvent on a worker thread (Bukkit refuses it on the server
     * thread), then PlayerPreLoginEvent if anything listens. PlayerLoginEvent, bans, whitelist and PlayerJoinEvent come from the
     * ported configuration-finished/placeNewPlayer code that runs for the virtual connection like for any client.
     */
    private fun preLogin(a: PlayerAttach, info: PlayerClientInfo?) {
        val ip = info?.let { runCatching { InetAddress.getByName(it.address) }.getOrNull() } ?: InetAddress.getLoopbackAddress()
        val props = ArrayListMultimap.create<String, Property>()
        a.properties.forEach { props.put(it.name, Property(it.name, it.value, it.signature)) }
        val gp = GameProfile(UUID(a.uuidHigh, a.uuidLow), a.name, PropertyMap(props))
        loginPool.execute {
            try {
                val bukkitProfile = com.destroystokyo.paper.profile.CraftPlayerProfile.asBukkitCopy(gp)
                val ev = org.bukkit.event.player.AsyncPlayerPreLoginEvent(a.name, ip, ip, gp.id(), false, bukkitProfile)
                org.bukkit.Bukkit.getPluginManager().callEvent(ev)
                val finalProfile = com.destroystokyo.paper.profile.CraftPlayerProfile.asAuthlibCopy(ev.playerProfile.also { it.complete(true) })
                val deny = if (ev.loginResult != org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED)
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(ev.kickMessage()) else null
                ready.add(Ready(a, finalProfile, deny, null).also { it.info = info })
            } catch (t: Throwable) { ready.add(Ready(a, null, null, t).also { it.info = info }) }
        }
    }

    private fun create(server: MinecraftServer, a: PlayerAttach, profile: GameProfile, info: PlayerClientInfo?): Virtual {
        val v = Virtual(a.playerId, a.name)
        val conn = Connection(PacketFlow.SERVERBOUND)
        v.conn = conn
        conn.hostname = info?.hostname ?: ""
        val remote = InetSocketAddress(info?.let { runCatching { InetAddress.getByName(it.address) }.getOrNull() } ?: InetAddress.getLoopbackAddress(), info?.port ?: 0)
        v.channel = net.fissionmc.net.VirtualChannel.create(remote, object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                val p = ch.pipeline()
                Connection.configureSerialization(p, PacketFlow.SERVERBOUND, true, null)
                conn.configurePacketHandler(p)
                p.addFirst("fission_out", Out(v))
            }
        })
        v.tap = net.fissionmc.packets.PacketTap.create(v.channel, profile.id(), profile.name(), com.github.retrooper.packetevents.protocol.player.ClientVersion.getById(a.protocol), log = { log.info("[fission] ${a.name}: $it") })
        v.channel.closeFuture().addListener {
            val removed = synchronized(sessions) { sessions.remove(v.id) }   // idempotent
            NodeBootstrap.link?.takeIf { it.isOpen }?.send(PlayerDetach(v.id, "closed on backend"))
            if (removed != null) log.info("[fission] relayed player ${v.name} closed")
        }
        synchronized(sessions) { sessions[v.id] = v }
        // like vanilla's login acknowledgement: switch both directions to configuration, register for ticking, start
        // Vanilla swaps the decoder for an 'unconfigured' placeholder when it decodes a TERMINAL packet (handshake/login ack). We skip
        // those phases, so do the swap by hand, otherwise the decoder stays on the initial handshake protocol and rejects every frame.
        v.channel.pipeline().replace("decoder", "inbound_config", UnconfiguredPipelineHandler.Inbound())
        conn.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND)
        val listener = ServerConfigurationPacketListenerImpl(server, conn, CommonListenerCookie.createInitial(profile, false))
        conn.setupInboundProtocol(ConfigurationProtocols.SERVERBOUND, listener)
        server.connection.connections.add(conn)
        v.channel.runPendingTasks()   // EmbeddedChannel runs netty tasks (the inbound protocol switch) only when asked
        listener.startConfiguration()
        log.info("[fission] relayed player ${a.name} attached (player ${a.playerId}) from ${remote.address.hostAddress}")
        return v
    }

    private fun closeAll(why: String) {
        val all = synchronized(sessions) { sessions.values.toList() }
        all.forEach { it.channel.close() }
        log.info("[fission] closed ${all.size} relayed players: $why")
    }
}
