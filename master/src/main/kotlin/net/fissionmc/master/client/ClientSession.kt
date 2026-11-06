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
    private enum class Mode { LIMBO, AWAIT_ACK, RELAY }
    private var mode = Mode.LIMBO
    val inRelay: Boolean get() = mode == Mode.RELAY
    val inLimbo: Boolean get() = mode == Mode.LIMBO
    private var configSent = false
    /** The client reported ServerboundPlayerLoaded (ClientPacketListener.notifyPlayerLoaded): its level is up, reconfiguring is safe. */
    private var clientLoaded = false
    private var graceOver = false
    /**
     * The latest ServerboundClientInformation payload (view distance, skin parts, main hand, chat settings...). Vanilla's client sends it once when
     * it connects (configuration, which the master answered itself) and again only when an option changes in play; it does NOT resend it for the
     * reconfiguration after StartConfiguration (ClientPacketListener.broadcastClientInformation). The backend's new player would keep
     * ClientInformation.createDefault() (view distance 2 = about 3 chunks), so the master replays it as the first configuration frame.
     */
    private var clientInfoPayload: ByteArray? = null
    private var relayId: Long? = null
    /** A backend switch in progress: the new backend's configuration is being completed by the master (see [Switch.kt]). */
    private class Switch(val id: Long, val nodeId: String) { val cfg = ConfigVirtualClient(); var inPlay = false; val startedNanos = System.nanoTime() }
    private var switching: Switch? = null
    private var graceTask: ScheduledFuture<*>? = null
    /** Waiting for the old backend to save the player before the new one is attached. */
    private var switchPending = false
    /** The network join line was sent (once per login: moving between backends, or back from the limbo, is not a join). */
    private var announcedJoin = false

    private fun announceArrival(backend: String?, fromBackend: String?) {
        if (!announcedJoin) { announcedJoin = true; server.announce(server.settings.announcements.join(playerName, backend)) }
        else if (fromBackend != null && backend != null) server.announce(server.settings.announcements.switched(playerName, backend))
    }
    private val cleanup = SwitchCleanup({ f -> runCatching { EventCreationUtil.createSendEvent(channel, user, null, io.netty.buffer.Unpooled.wrappedBuffer(f), false) }.getOrNull() }, { server.log("[client $playerName] $it") })
    /** Keepalive ids the CURRENT backend sent and the client has not answered yet (a stale answer for a previous backend must not reach this one). */
    private val backendKeepAlives = java.util.ArrayDeque<Long>()
    /** Entity id the current Login gave the client (informational: the scripted test asserts it follows the backend's Login). */
    @Volatile var selfEntityId = -1; private set
    /** Backend this client is currently relayed to (null in limbo). */
    @Volatile var currentNodeId: String? = null; private set
    @Volatile var chatGlobal = server.settings.chat.defaultGlobal
    /** Permission level the current backend reports for this player (see [net.fissionmc.protocol.PlayerPermissionReport]). */
    @Volatile var backendLevel = 0; private set
    override fun backendPermission(level: Int) { backendLevel = level }
    /**
     * Admin for the master's own commands and command tree: in the master's admin set (which is also operator level 4 on every backend), or
     * operator level 4 on the backend the player is on right now (`/op` on that backend).
     */
    val isAdmin: Boolean get() = server.settings.isAdmin(uuid) || backendLevel >= 4

    /** The master's admin set changed: tell the player's backend (it resends the command tree, which the master patches again). */
    fun pushPermission() { channel.eventLoop().execute {
        val id = relayId ?: return@execute; val u = uuid ?: return@execute
        server.relay?.sendTo(id, net.fissionmc.protocol.PlayerPermission(id, u.mostSignificantBits, u.leastSignificantBits, if (server.settings.isAdmin(u)) 4 else 0))
    } }
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
    val bindingId: Long? get() = relayId
    val uuid: java.util.UUID? get() = prof?.uuid
    /** True when the client is in the PLAY phase on a backend (global chat and `/fission send` apply). */
    val inBackendPlay: Boolean get() = mode == Mode.RELAY && tracker.clientState == ConnectionState.PLAY && tracker.serverState == ConnectionState.PLAY

    override fun channelActive(ctx: ChannelHandlerContext) {
        channel = ctx.channel()
        user = User(channel, ConnectionState.HANDSHAKING, ClientVersion.UNKNOWN, UserProfile(null, null))
        ctx.fireChannelActive()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        keepAlive?.cancel(false)
        if (announcedJoin && registered) { announcedJoin = false; server.unregister(this); registered = false; server.announce(server.settings.announcements.leave(playerName, currentNodeId)) }
        relayId?.let { server.relay?.detach(it, "client left") }
        switching?.let { server.relay?.detach(it.id, "client left") }
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

    override fun deliver(rawFrame: ByteArray) = deliverFrom(relayId ?: -1L, rawFrame)

    override fun deliverFrom(bindingId: Long, rawFrame: ByteArray) { channel.eventLoop().execute {
        val sw = switching
        if (sw != null && sw.id == bindingId) { onSwitchFrame(sw, rawFrame); return@execute }
        if (bindingId != relayId) return@execute   // a binding that was already detached
        deliverRelay(rawFrame)
    } }

    private fun deliverRelay(rawFrame: ByteArray) {
        if (mode != Mode.RELAY && mode != Mode.AWAIT_ACK) return
        // A frame whose id does not exist in the phase the client is in would make the vanilla client drop the connection
        // ("Received unknown packet id N" in IdDispatchCodec). Vanilla's server cannot produce one (its outbound protocol is switched
        // together with the client's: ServerGamePacketListenerImpl.switchToConfig / handleConfigurationFinished); a backend that does
        // is out of step, so say so loudly and keep it away from the client.
        val state = tracker.serverState; val id = tracker.packetId(rawFrame)
        if (PacketType.getById(com.github.retrooper.packetevents.protocol.PacketSide.SERVER, state, ClientVersion.V_26_3, id) == null) {
            server.log("[client $playerName] DROPPED backend frame id $id: not a clientbound $state packet (client phase=$state)")
            return
        }
        val frame = toClient(rawFrame) ?: return
        if (state == ConnectionState.PLAY) {
            cleanup.observe(frame)
            if (id == PlayIds.keepAliveS2C && frame.size >= 9) { backendKeepAlives.add(java.nio.ByteBuffer.wrap(PlayIds.payload(frame)).long); while (backendKeepAlives.size > 8) backendKeepAlives.poll() }
            if (id == PlayIds.commands) { val patched = ChatCommandTree.patch(frame, isAdmin, server.log); if (patched != null) { writeRelay(patched); return } }
        }
        when (mode) {
            Mode.RELAY -> writeRelay(frame)
            else -> if (pendingFromBackend.size < 20_000) pendingFromBackend.add(frame) else channel.close()
        }
    }

    private fun writeRelay(frame: ByteArray) {
        if (!channel.isActive) return
        channel.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(frame))
        if (channel.bytesBeforeUnwritable() == 0L) overflow++ else overflow = 0
        if (overflow > 20_000) { server.log("[client $playerName] too slow, dropping"); channel.close() }
    }
    private var overflow = 0

    override fun detached(reason: String, backendLost: Boolean) = detachedFrom(relayId ?: -1L, reason, backendLost)

    override fun detachedFrom(bindingId: Long, reason: String, backendLost: Boolean) { channel.eventLoop().execute {
        val sw = switching
        if (sw != null && sw.id == bindingId) {   // the backend we were switching to refused or died: the player stays where it is
            switching = null; restoreDataOwner()
            server.log("[client $playerName] switch to ${sw.nodeId} failed: $reason")
            tell("Could not move to ${sw.nodeId}: ${reason.removePrefix("kick:")}")
            return@execute
        }
        if (bindingId != relayId) return@execute
        relayId = null; currentNodeId = null; backendLevel = 0
        server.log("[client $playerName] backend session ended: $reason")
        if (mode != Mode.RELAY && mode != Mode.AWAIT_ACK) { channel.close(); return@execute }
        // the client's real phase is what the tracker saw, not what we last set by hand
        user.decoderState = tracker.clientState; user.encoderState = tracker.serverState
        when {
            // the backend died while the client plays: back to the limbo in the PLAY state (no Reconfiguring screen), or on to the backend we are switching to
            backendLost && mode == Mode.RELAY && tracker.clientState == ConnectionState.PLAY && tracker.serverState == ConnectionState.PLAY ->
                if (switching == null) toLimboSeamless() else server.log("[client $playerName] waiting for the switch to ${switching!!.nodeId}")
            reason.startsWith("kick:") -> disconnect(reason.removePrefix("kick:"))
            else -> channel.close()   // mid-configuration there is no way back, the client has to reconnect
        }
    } }

    /** Runs [r] on this client's event loop after [delayMs]. */
    fun schedule(delayMs: Long, r: Runnable) { channel.eventLoop().schedule(r, delayMs, TimeUnit.MILLISECONDS) }

    /** A system chat line from the master itself (only while the client is in the PLAY phase). */
    fun tell(text: String) = tell(Component.text(text))
    fun tell(c: Component) { channel.eventLoop().execute {
        if (!channel.isActive || mode != Mode.RELAY || tracker.serverState != ConnectionState.PLAY) return@execute
        writeRelay(Limbo.encode(com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage(false, c)))
    } }

    /** The backend died while the client was playing: put it into the limbo without leaving the PLAY state (cleanup, then the limbo's Login). */
    private fun toLimboSeamless() {
        server.log("[client] $playerName: backend lost, back to limbo")
        pendingFromBackend.clear(); tap = null; tracker = net.fissionmc.packets.ClientStateTracker(net.fissionmc.packets.StateIds.forVersion(), ConnectionState.PLAY)
        mode = Mode.LIMBO
        startLimboPlay(cleanup.cleanupFrames())
    }

    /** Moves a limbo player to a backend (if one is alive), see [startSwitch]. */
    fun tryAttach() { channel.eventLoop().execute { if (mode == Mode.LIMBO) startSwitch(null) } }   // timers and backend joins only ever move LIMBO players

    /**
     * Moves the client to [nodeId] (round robin when null) without leaving the PLAY state. The new backend starts its CONFIGURATION with
     * the master as its client ([ConfigVirtualClient]); its Login completes the switch ([completeSwitch]). If the backend's configuration
     * differs from the one the client got, the old StartConfiguration path is used for this switch ([classicAttach]).
     * [report] receives a short reason when nothing could be started.
     */
    fun startSwitch(nodeId: String?, report: (String) -> Unit = {}) { channel.eventLoop().execute {
        if (!channel.isActive) return@execute
        if (mode == Mode.LIMBO && user.decoderState != ConnectionState.PLAY) return@execute
        if (switching != null || switchPending) { report("a switch is already running"); return@execute }
        if (mode == Mode.LIMBO) {
            // Vanilla's client only accepts the second Login once it finished loading the first level it got (the real-client failure
            // happened when StartConfiguration followed the limbo burst immediately); a client that never says so is moved after a grace period.
            if (!clientLoaded && !graceOver) { report("the player is still loading the limbo"); return@execute }
        } else if (mode != Mode.RELAY || tracker.clientState != ConnectionState.PLAY || tracker.serverState != ConnectionState.PLAY) { report("the player is busy"); return@execute }
        if (nodeId != null && nodeId == currentNodeId) { report("already on $nodeId"); return@execute }
        // leaving a backend: it saves the player first, so the next backend starts from the player's latest state (position, inventory, ...)
        val from = relayId; val id = uuid
        if (mode == Mode.RELAY && from != null && id != null && server.relay?.playerData != null) {
            switchPending = true
            server.relay!!.flushPlayerData(from, id, 3000) { channel.eventLoop().execute {
                switchPending = false
                if (channel.isActive && mode == Mode.RELAY && relayId == from) attachSwitch(nodeId, report)
            } }
        } else attachSwitch(nodeId, report)
    } }

    private fun attachSwitch(nodeId: String?, report: (String) -> Unit) {
        val id = server.relay?.attach(this, nodeId)
        if (id == null) { report(if (nodeId == null) "no backend is alive" else "backend $nodeId is not connected"); return }
        val node = server.relay!!.nodeIdOf(id) ?: "?"
        switching = Switch(id, node)
        // the client's real options go first, like in the first configuration of a fresh vanilla connection
        clientInfoPayload?.let { p -> server.relay?.toBackend(id, PlayIds.frame(0, p)) }
        server.log("[client] $playerName -> backend $node (player $id), seamless")
    }

    /** A failed switch leaves the player where it is: the data owner goes back to that backend. */
    private fun restoreDataOwner() { uuid?.let { server.relay?.setDataOwner(it, currentNodeId) } }

    private fun onSwitchFrame(sw: Switch, frame: ByteArray) {
        if (!sw.inPlay) {
            val r = sw.cfg.onFrame(frame)
            r.replies.forEach { server.relay?.toBackend(sw.id, it) }
            if (r.abort != null) {
                server.log("[client $playerName] switch to ${sw.nodeId}: ${r.abort}")
                switching = null; server.relay?.detach(sw.id, "switch aborted"); restoreDataOwner()
                if (r.mismatch) { server.log("[client $playerName] falling back to StartConfiguration for ${sw.nodeId}"); classicAttach(sw.nodeId) }
                else tell("Could not move to ${sw.nodeId}: ${r.abort}")
                return
            }
            if (r.finished) sw.inPlay = true
            return
        }
        if (Limbo.idOf(frame) != PlayIds.login) { server.log("[client $playerName] dropped play frame ${Limbo.idOf(frame)} from ${sw.nodeId} before its Login"); return }
        completeSwitch(sw, frame)
    }

    /** The new backend's Login arrived: leave the old binding, clean up what a new level does not reset, hand the client the Login. */
    private fun completeSwitch(sw: Switch, login: ByteArray) {
        val old = relayId
        val currentNodeIdBefore = currentNodeId
        relayId = sw.id; currentNodeId = sw.nodeId; switching = null
        if (old != null) server.relay?.detach(old, "switched to ${sw.nodeId}")
        keepAlive?.cancel(false); keepAlive = null
        backendKeepAlives.clear()
        pendingFromBackend.clear()
        mode = Mode.RELAY
        tap = net.fissionmc.packets.PacketTap.create(channel, prof?.uuid, prof?.name, user.clientVersion, server.log, ConnectionState.PLAY)
        tracker = tap?.tracker ?: net.fissionmc.packets.ClientStateTracker(net.fissionmc.packets.StateIds.forVersion(), ConnectionState.PLAY)
        val cleanupFrames = cleanup.cleanupFrames()
        cleanupFrames.forEach { channel.write(io.netty.buffer.Unpooled.wrappedBuffer(it)) }
        selfEntityId = if (login.size >= 5) java.nio.ByteBuffer.wrap(PlayIds.payload(login)).int else -1
        val out = toClient(login)
        if (out != null) channel.write(io.netty.buffer.Unpooled.wrappedBuffer(out))
        // a second Login keeps the old LocalPlayer (bound to the old level): Respawn makes the client build a new one (see RespawnFrames)
        runCatching { RespawnFrames.fromLogin(login) }.onSuccess { channel.write(io.netty.buffer.Unpooled.wrappedBuffer(it)) }.onFailure { server.log("[client $playerName] no Respawn after the Login: $it") }
        channel.flush()
        announceArrival(sw.nodeId, if (old != null) currentNodeIdBefore else null)
        server.log("[client] $playerName now on ${sw.nodeId} (Login entity id $selfEntityId, switch took ${(System.nanoTime() - sw.startedNanos) / 1_000_000} ms, ${cleanupFrames.size} cleanup frames)")
    }

    /** The old way: StartConfiguration, the client reconfigures (shows "Reconfiguring"). Used only when the backend's configuration differs. */
    private fun classicAttach(nodeId: String?) {
        val old = relayId
        val id = server.relay?.attach(this, nodeId)
        if (id == null) { tell("Could not move to ${nodeId ?: "a backend"}: not connected"); return }
        if (old != null) server.relay?.detach(old, "switch (classic)")
        relayId = id; currentNodeId = server.relay?.nodeIdOf(id); mode = Mode.AWAIT_ACK
        keepAlive?.cancel(false); backendKeepAlives.clear(); pendingFromBackend.clear()
        tap = net.fissionmc.packets.PacketTap.create(channel, prof?.uuid, prof?.name, user.clientVersion, server.log)
        tracker = tap?.tracker ?: net.fissionmc.packets.ClientStateTracker(net.fissionmc.packets.StateIds.forVersion())
        user.decoderState = ConnectionState.PLAY; user.encoderState = ConnectionState.PLAY
        send(com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerConfigurationStart())
        server.log("[client] $playerName -> backend $currentNodeId (player $id), classic StartConfiguration")
    }

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
                if (tracker.clientState == ConnectionState.PLAY && interceptClient(bytes)) return
                val out = fromClient(bytes)
                if (id != null && out != null && !server.relay!!.toBackend(id, out)) pauseReading()
            } finally { ReferenceCountUtil.release(buf) }
            return
        }
        try {
            val st = user.decoderState   // PacketEvents' internal listener advances the user's state while the event is fired
            val event = EventCreationUtil.createReceiveEvent(channel, user, null, buf, false)
            if (!net.fissionmc.packets.PacketBus.fireReceive(event)) return   // a listener cancelled it
            if (event.packetType == PacketType.Configuration.Client.CLIENT_SETTINGS || event.packetType == PacketType.Play.Client.CLIENT_SETTINGS) {
                val all = ByteArray(buf.writerIndex()); buf.getBytes(0, all)
                val idLen = VarInts.size(Limbo.idOf(all)); clientInfoPayload = all.copyOfRange(idLen, all.size)
            }
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

    /** Master-owned client packets in the PLAY phase (keepalive filter, `/chat`, `/fission send`). True = swallowed here. */
    private fun interceptClient(bytes: ByteArray): Boolean {
        when (val pid = tracker.packetId(bytes)) {
            PlayIds.keepAliveC2S -> {
                val p = PlayIds.payload(bytes); if (p.size < 8) return false
                return !backendKeepAlives.remove(java.nio.ByteBuffer.wrap(p).long)   // an answer to a previous backend's challenge would kick the player on this one
            }
            PlayIds.chatCommand, PlayIds.chatCommandSigned -> {
                val cmd = Strings.readFirst(bytes) ?: return false
                return MasterCommands.handle(this, server, cmd)
            }
            PlayIds.commandSuggestion -> {   // tab completion of the master's own `/fission send <player> <backend>` (admins only)
                if (!isAdmin) return false
                val reply = MasterCommands.suggest(bytes, server) ?: return false
                writeRelay(reply); return true
            }
            PlayIds.chat -> {
                val msg = Strings.readFirst(bytes) ?: return false
                onChat(msg)   // never swallowed: a dropped signed message would break the client's signature chain; the backend decides what happens to it
                return false
            }
            else -> { if (pid < 0) return false }
        }
        return false
    }

    /** Global chat: tell the sender's backend to run the event path for this message (see [GlobalChatHub]). */
    private fun onChat(text: String) {
        if (!chatGlobal) return
        val id = relayId ?: return
        server.chat?.onGlobalMessage(this, id, text)
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
                // replay the recorded configuration up to the known-packs request; the rest follows when the client answers
                Limbo.sendRaw(channel, Limbo.config.beforeKnownPacks)
            }
            else -> {}
        }
    }

    private fun finishAuth(p: PlayerProfile) {
        prof = p
        if (!server.register(this, p)) { disconnect("You are already connected"); return }
        registered = true
        server.settings.adminStore.bindOnLogin(p.name, p.uuid)   // bootstrap: a name entry in the admin list becomes this uuid
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
                Limbo.sendRaw(channel, Limbo.config.afterKnownPacks)
            }
            PacketType.Configuration.Client.CONFIGURATION_END_ACK -> enterPlay()
            else -> {}
        }
    }

    // ---- limbo play ----
    private fun enterPlay() { startLimboPlay(emptyList()) }

    /**
     * The limbo's play entry sequence (Login first). [before] is sent ahead of it when the client is already in PLAY (coming back from a
     * backend: cleanup frames); the second Login makes the client build a new level, like vanilla's ClientPacketListener.handleLogin does.
     */
    private fun startLimboPlay(before: List<ByteArray>) {
        clientLoaded = false; graceOver = false   // a re-entered limbo (backend lost) loads its level again
        user.decoderState = ConnectionState.PLAY; user.encoderState = ConnectionState.PLAY
        currentNodeId = null; backendKeepAlives.clear()
        val frames = Limbo.playFrames(server.config.maxPlayers, Limbo.config.overworldDimensionId)
        // coming back from a backend the client keeps its old LocalPlayer: Respawn right after the limbo's Login (see RespawnFrames)
        val withRespawn = if (before.isEmpty()) frames else listOf(frames[0], RespawnFrames.fromLogin(frames[0])) + frames.drop(1)
        Limbo.sendRaw(channel, before + withRespawn)
        selfEntityId = Limbo.ENTITY_ID
        server.log("[client] $playerName joined limbo")
        graceTask?.cancel(false)
        graceTask = channel.eventLoop().schedule({ graceOver = true; tryAttach() }, LOAD_GRACE_SECONDS, TimeUnit.SECONDS)
        lastKeepAliveAck = System.nanoTime()
        keepAlive?.cancel(false)
        keepAlive = channel.eventLoop().scheduleAtFixedRate({
            if (mode != Mode.LIMBO) return@scheduleAtFixedRate   // nothing play-state after StartConfiguration (ServerGamePacketListenerImpl.switchToConfig swaps the outbound protocol at once)
            if (System.nanoTime() - lastKeepAliveAck > TimeUnit.SECONDS.toNanos(30)) { disconnect("Timed out"); return@scheduleAtFixedRate }
            lastKeepAliveSent = System.nanoTime()
            send(WrapperPlayServerKeepAlive(lastKeepAliveSent and 0x7FFFFFFFFFFFL))
        }, 10, 10, TimeUnit.SECONDS)
    }

    private fun onPlay(e: PacketReceiveEvent) {
        if (mode == Mode.AWAIT_ACK) {
            if (e.packetType == PacketType.Play.Client.CONFIGURATION_ACK) {
                user.decoderState = ConnectionState.CONFIGURATION; user.encoderState = ConnectionState.CONFIGURATION
                // first frame of the backend's configuration: the client's real options (config-state ClientInformation has packet id 0)
                clientInfoPayload?.let { p -> val f = ByteArray(1 + p.size); System.arraycopy(p, 0, f, 1, p.size); relayId?.let { id -> server.relay?.toBackend(id, f) } }
                mode = Mode.RELAY
                announceArrival(currentNodeId, null)
                while (pendingFromBackend.isNotEmpty()) channel.write(io.netty.buffer.Unpooled.wrappedBuffer(pendingFromBackend.poll()))
                channel.flush()
            }
            return
        }
        if (e.packetType == PacketType.Play.Client.PLAYER_LOADED && !clientLoaded) { clientLoaded = true; tryAttach() }
        if (e.packetType == PacketType.Play.Client.KEEP_ALIVE) {
            WrapperPlayClientKeepAlive(e); lastKeepAliveAck = System.nanoTime()
        }
        // everything else is ignored in limbo (movement, chat, settings...)
    }

    companion object { const val PROTOCOL = 777; var LOAD_GRACE_SECONDS = 10L }
}
