package net.fissionmc.master.client

import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.ConnectionState
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.util.EventCreationUtil
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerLoginSuccess
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerSetCompression
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisconnect
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerDisconnect
import com.github.retrooper.packetevents.wrapper.status.client.WrapperStatusClientPing
import com.github.retrooper.packetevents.wrapper.status.server.WrapperStatusServerPong
import com.github.retrooper.packetevents.wrapper.status.server.WrapperStatusServerResponse
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake.ConnectionIntention
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import net.fissionmc.protocol.MC_VERSION
import net.kyori.adventure.text.Component
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/**
 * One client connection on the master: handshake, status, login (+ online-mode auth, encryption, compression),
 * configuration, then limbo play. Runs on the channel's event loop; it never blocks (auth runs on [ClientServer.auth]).
 * All packets are decoded/encoded with PacketEvents wrappers.
 */
class ClientSession(private val server: ClientServer) : ChannelInboundHandlerAdapter(), net.fissionmc.master.RelayClient {
    private enum class Mode { LIMBO, AWAIT_ACK, RELAY, REJOIN }
    private var mode = Mode.LIMBO
    private var configSent = false
    private var relayId: Long? = null
    private var hostName = ""
    private var tap: net.fissionmc.packets.PacketTap? = null
    private var tracker = net.fissionmc.packets.ClientStateTracker(net.fissionmc.packets.StateIds.forVersion())
    override val remoteAddress: java.net.InetSocketAddress? get() = channel.remoteAddress() as? java.net.InetSocketAddress
    override val virtualHost: String get() = hostName

    /** Client frame heading for the backend through the packet bus; null = a listener dropped it. */
    private fun fromClient(bytes: ByteArray): ByteArray? = tap?.fromClient(bytes) ?: if (tap == null) bytes.also { tracker.onClientFrame(it) } else null
    /** Backend frame heading for the client through the packet bus; null = dropped. */
    private fun toClient(bytes: ByteArray): ByteArray? = tap?.toClient(bytes) ?: if (tap == null) bytes.also { tracker.onServerFrame(it) } else null
    private val pendingFromBackend = java.util.ArrayDeque<ByteArray>()
    private lateinit var channel: Channel
    private lateinit var user: User
    private var protocolVersion = 0
    private var username = ""
    private var verifyToken = ByteArray(0)
    private var prof: PlayerProfile? = null
    override val profile: PlayerProfile get() = prof!!
    override val protocol: Int get() = protocolVersion
    private var keepAlive: ScheduledFuture<*>? = null
    private var lastKeepAliveSent = 0L
    @Volatile private var lastKeepAliveAck = System.nanoTime()
    private var registered = false
    val state: ConnectionState get() = user.decoderState
    val playerName: String get() = prof?.name ?: username

    override fun channelActive(ctx: ChannelHandlerContext) {
        channel = ctx.channel()
        user = User(channel, ConnectionState.HANDSHAKING, ClientVersion.UNKNOWN, UserProfile(null, null))
        ctx.fireChannelActive()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        keepAlive?.cancel(false)
        relayId?.let { server.relay?.detach(it, "client left") }
        if (registered) server.unregister(this)
        ctx.fireChannelInactive()
    }

    /** Backpressure: the node link is saturated, stop reading this client until it drains. */
    private fun pauseReading() {
        if (!channel.config().isAutoRead) return
        channel.config().isAutoRead = false
        channel.eventLoop().scheduleWithFixedDelay({
            val id = relayId
            if (id == null || !channel.isActive) throw RuntimeException("stop")
            if (server.relay!!.isWritable(id)) { channel.config().isAutoRead = true; throw RuntimeException("stop") }
        }, 20, 20, TimeUnit.MILLISECONDS)
    }

    override fun deliver(rawFrame: ByteArray) { channel.eventLoop().execute {
        if (mode != Mode.RELAY && mode != Mode.AWAIT_ACK) return@execute
        val frame = toClient(rawFrame) ?: return@execute
        when (mode) {
            Mode.RELAY -> {
                if (!channel.isActive) return@execute
                if (!channel.isWritable && channel.bytesBeforeWritable() == 0L) { /* flush pending below */ }
                channel.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(frame))
                if (channel.bytesBeforeUnwritable() == 0L) overflow++ else overflow = 0
                if (overflow > 20_000) { server.log("[client $playerName] too slow, dropping"); channel.close() }
            }
            else -> if (pendingFromBackend.size < 20_000) pendingFromBackend.add(frame) else channel.close()
        }
    } }
    private var overflow = 0

    override fun detached(reason: String, backendLost: Boolean) { channel.eventLoop().execute {
        relayId = null
        server.log("[client $playerName] backend session ended: $reason")
        if (mode != Mode.RELAY && mode != Mode.AWAIT_ACK) { channel.close(); return@execute }
        // the client's real phase is what the tracker saw, not what we last set by hand
        user.decoderState = tracker.clientState; user.encoderState = tracker.serverState
        when {
            backendLost && mode == Mode.RELAY && tracker.clientState == ConnectionState.PLAY && tracker.serverState == ConnectionState.PLAY -> backToLimbo()
            reason.startsWith("kick:") -> disconnect(reason.removePrefix("kick:"))
            else -> channel.close()   // mid-configuration there is no way back, the client has to reconnect
        }
    } }

    /** The backend died while the client was playing: reconfigure the client into limbo (play -> configuration -> limbo). */
    private fun backToLimbo() {
        mode = Mode.REJOIN; pendingFromBackend.clear(); configSent = false
        server.log("[client] $playerName: backend lost, back to limbo")
        send(com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerConfigurationStart())
    }

    /** Moves a limbo player to a backend (if one is alive): play -> configuration, then the backend's configuration/play is relayed. */
    fun tryAttach() { channel.eventLoop().execute {
        if (mode != Mode.LIMBO || user.decoderState != ConnectionState.PLAY || !channel.isActive) return@execute
        val id = server.relay?.attach(this) ?: return@execute
        relayId = id; mode = Mode.AWAIT_ACK
        tap = net.fissionmc.packets.PacketTap.create(channel, prof?.uuid, prof?.name, user.clientVersion, server.log)
        tracker = tap?.tracker ?: net.fissionmc.packets.ClientStateTracker(net.fissionmc.packets.StateIds.forVersion())
        keepAlive?.cancel(false)
        send(com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerConfigurationStart())
        server.log("[client] $playerName -> backend (player $id)")
    } }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        server.log("[client ${channel.remoteAddress()}] ${cause.javaClass.simpleName}: ${cause.message}")
        ctx.close()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val buf = msg as ByteBuf
        if (mode == Mode.RELAY) {   // opaque relay: frame goes to the backend untouched (P3 listeners hook in here)
            try {
                val bytes = ByteArray(buf.readableBytes()); buf.readBytes(bytes)
                val id = relayId
                val out = fromClient(bytes)
                if (id != null && out != null && !server.relay!!.toBackend(id, out)) pauseReading()
            } finally { ReferenceCountUtil.release(buf) }
            return
        }
        try {
            val st = user.decoderState   // PacketEvents' internal listener advances the user's state while the event is fired
            val event = EventCreationUtil.createReceiveEvent(channel, user, null, buf, false)
            if (!net.fissionmc.packets.PacketBus.fireReceive(event)) return   // a listener cancelled it
            if (System.getProperty("fission.debug.packets") != null) server.log("[client] $username <- $st ${event.packetType}")
            when (st) {
                ConnectionState.HANDSHAKING -> onHandshake(event)
                ConnectionState.STATUS -> onStatus(event)
                ConnectionState.LOGIN -> onLogin(event)
                ConnectionState.CONFIGURATION -> onConfiguration(event)
                ConnectionState.PLAY -> onPlay(event)
            }
        } catch (t: Throwable) { exceptionCaught(ctx, t) }
        finally { ReferenceCountUtil.release(buf) }
    }

    // ---- sending ----
    fun send(w: PacketWrapper<*>) {
        
        w.prepareForSend(channel, true, false)
        channel.writeAndFlush(w.buffer).addListener { f -> if (!f.isSuccess) server.log("[client $playerName] write failed: ${f.cause()}") }
    }
    private fun sendAll(ws: List<PacketWrapper<*>>) { ws.forEach { w -> w.prepareForSend(channel, true, false); channel.write(w.buffer) }; channel.flush() }

    fun disconnect(reason: String) {
        if (!channel.isActive) return
        server.log("[client ${channel.remoteAddress()}] disconnect: $reason")
        val c = Component.text(reason)
        val w: PacketWrapper<*>? = when (user.encoderState) {
            ConnectionState.LOGIN -> WrapperLoginServerDisconnect(c)
            ConnectionState.CONFIGURATION -> WrapperConfigServerDisconnect(c)
            ConnectionState.PLAY -> WrapperPlayServerDisconnect(c)
            else -> null
        }
        if (w == null) { channel.close(); return }
        w.prepareForSend(channel, true, false)
        channel.writeAndFlush(w.buffer).addListener { channel.close() }
    }

    // ---- handshake / status ----
    private fun onHandshake(e: PacketReceiveEvent) {
        val h = WrapperHandshakingClientHandshake(e)
        protocolVersion = h.protocolVersion
        hostName = h.serverAddress
        server.log("[client] handshake protocol=${h.protocolVersion} host=${h.serverAddress}:${h.serverPort} intention=${h.intention}")
        val next = when (h.intention) {
            ConnectionIntention.STATUS -> ConnectionState.STATUS
            ConnectionIntention.LOGIN -> ConnectionState.LOGIN
            else -> { channel.close(); return }   // transfer intention (3) is not accepted
        }
        user.setClientVersion(ClientVersion.getById(protocolVersion))
        user.decoderState = next; user.encoderState = next
    }

    private fun onStatus(e: PacketReceiveEvent) {
        when (e.packetType) {
            PacketType.Status.Client.REQUEST -> send(WrapperStatusServerResponse(server.statusJson()))
            PacketType.Status.Client.PING -> {
                send(WrapperStatusServerPong(WrapperStatusClientPing(e).time)); channel.close()
            }
            else -> channel.close()
        }
    }

    // ---- login ----
    private fun onLogin(e: PacketReceiveEvent) {
        when (e.packetType) {
            PacketType.Login.Client.LOGIN_START -> {
                if (username.isNotEmpty()) { channel.close(); return }
                if (protocolVersion != PROTOCOL) { disconnect("Unsupported client version, please use Minecraft $MC_VERSION"); return }
                username = WrapperLoginClientLoginStart(e).username
                if (!ValidName.ok(username)) { disconnect("Invalid username"); return }
                if (server.config.onlineMode) {
                    verifyToken = LoginCrypto.newToken()
                    send(WrapperLoginServerEncryptionRequest("", server.keyPair.public, verifyToken, true))
                } else finishAuth(PlayerProfile(LoginCrypto.offlineUuid(username), username))
            }
            PacketType.Login.Client.ENCRYPTION_RESPONSE -> {
                if (!server.config.onlineMode || verifyToken.isEmpty()) { channel.close(); return }
                val r = WrapperLoginClientEncryptionResponse(e)
                val secret: ByteArray
                try {
                    val tok = LoginCrypto.rsaDecrypt(server.keyPair.private, r.encryptedVerifyToken.orElseThrow())
                    if (!tok.contentEquals(verifyToken)) { disconnect("Protocol error"); return }
                    secret = LoginCrypto.rsaDecrypt(server.keyPair.private, r.encryptedSharedSecret)
                } catch (_: Exception) { disconnect("Protocol error"); return }
                verifyToken = ByteArray(0)
                channel.pipeline().addFirst("encrypt", CipherEncoder(LoginCrypto.aes(secret, Cipher.ENCRYPT_MODE)))
                channel.pipeline().addAfter("encrypt", "decrypt", CipherDecoder(LoginCrypto.aes(secret, Cipher.DECRYPT_MODE)))
                val hash = LoginCrypto.serverHash("", secret, server.keyPair.public.encoded)
                // auth off the event loop; the result comes back onto it
                server.log("[client] $username: verifying with the session server")
                server.authenticator.authenticate(username, hash).whenComplete { p, err ->
                    if (err != null) server.log("[client] $username: authentication error: $err")
                    channel.eventLoop().execute {
                        try {
                            if (err != null || p == null) disconnect("Failed to verify username") else finishAuth(p)
                        } catch (t: Throwable) { exceptionCaught(channel.pipeline().context(this), t) }
                    }
                }
            }
            PacketType.Login.Client.LOGIN_SUCCESS_ACK -> {
                user.decoderState = ConnectionState.CONFIGURATION; user.encoderState = ConnectionState.CONFIGURATION
                // announce the core pack, then registries + end (the client answers known packs; we do not wait for it)
                send(Limbo.knownPacks())
            }
            else -> {}
        }
    }

    private fun finishAuth(p: PlayerProfile) {
        prof = p
        if (!server.register(this, p)) { disconnect("You are already connected"); return }
        registered = true
        val t = server.config.compressionThreshold
        if (t >= 0) {
            send(WrapperLoginServerSetCompression(t))
            channel.pipeline().addAfter("prepender", "compress", CompressionEncoder(t))
            channel.pipeline().addAfter("prepender", "decompress", DecompressionDecoder())
        }
        val up = UserProfile(p.uuid, p.name)
        p.properties.forEach { up.textureProperties.add(com.github.retrooper.packetevents.protocol.player.TextureProperty(it.name, it.value, it.signature)) }
        user.profile.uuid = p.uuid; user.profile.name = p.name
        server.log("[client] ${p.name}: sending login success (${up.textureProperties.size} properties)")
        send(WrapperLoginServerLoginSuccess(up))
    }

    // ---- configuration ----
    private fun onConfiguration(e: PacketReceiveEvent) {
        when (e.packetType) {
            PacketType.Configuration.Client.SELECT_KNOWN_PACKS -> if (!configSent) {
                configSent = true
                sendAll(Limbo.registryPackets() + Limbo.configurationEnd())
            }
            PacketType.Configuration.Client.CONFIGURATION_END_ACK -> enterPlay()
            else -> {}
        }
    }

    // ---- limbo play ----
    private fun enterPlay() {
        user.decoderState = ConnectionState.PLAY; user.encoderState = ConnectionState.PLAY
        sendAll(listOf(
            Limbo.joinGame(server.config.maxPlayers), Limbo.position(), Limbo.startLoadingChunks(),
            Limbo.viewPosition(), Limbo.emptyChunk(), Limbo.chat("You are connected to the FissionMC master. Waiting for a backend..."),
        ))
        server.log("[client] $playerName joined limbo")
        tryAttach()
        lastKeepAliveAck = System.nanoTime()
        keepAlive = channel.eventLoop().scheduleAtFixedRate({
            if (System.nanoTime() - lastKeepAliveAck > TimeUnit.SECONDS.toNanos(30)) { disconnect("Timed out"); return@scheduleAtFixedRate }
            lastKeepAliveSent = System.nanoTime()
            send(WrapperPlayServerKeepAlive(lastKeepAliveSent and 0x7FFFFFFFFFFFL))
        }, 10, 10, TimeUnit.SECONDS)
    }

    private fun onPlay(e: PacketReceiveEvent) {
        if (mode == Mode.REJOIN) {
            if (e.packetType == PacketType.Play.Client.CONFIGURATION_ACK) {
                user.decoderState = ConnectionState.CONFIGURATION; user.encoderState = ConnectionState.CONFIGURATION
                mode = Mode.LIMBO; tap = null; send(Limbo.knownPacks())
            }
            return
        }
        if (mode == Mode.AWAIT_ACK) {
            if (e.packetType == PacketType.Play.Client.CONFIGURATION_ACK) {
                user.decoderState = ConnectionState.CONFIGURATION; user.encoderState = ConnectionState.CONFIGURATION
                mode = Mode.RELAY
                while (pendingFromBackend.isNotEmpty()) channel.write(io.netty.buffer.Unpooled.wrappedBuffer(pendingFromBackend.poll()))
                channel.flush()
            }
            return
        }
        if (e.packetType == PacketType.Play.Client.KEEP_ALIVE) {
            WrapperPlayClientKeepAlive(e); lastKeepAliveAck = System.nanoTime()
        }
        // everything else is ignored in limbo (movement, chat, settings...)
    }

    companion object { const val PROTOCOL = 777 }
}
