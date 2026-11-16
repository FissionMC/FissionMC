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
import net.fissionmc.net.trace.Trace
import net.fissionmc.net.trace.TraceEvent
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
class ClientSession(private val server: ClientServer) : ChannelInboundHandlerAdapter(), net.fissionmc.master.RelayClient, TabViewer, net.fissionmc.master.seamless.GhostViewer {
    private enum class Mode { LIMBO, AWAIT_ACK, RELAY }
    private var mode = Mode.LIMBO
    val inRelay: Boolean get() = mode == Mode.RELAY
    val inLimbo: Boolean get() = mode == Mode.LIMBO
    private var configSent = false
    /** The client reported ServerboundPlayerLoaded (ClientPacketListener.notifyPlayerLoaded): its level is up, reconfiguring is safe. */
    private var clientLoaded = false
    private var graceOver = false
    /** Phase timing of the first entry (limbo, first backend); logged once as a `[join]` line. */
    private var join: JoinTiming? = null
    /**
     * The latest ServerboundClientInformation payload (view distance, skin parts, main hand, chat settings...). Vanilla's client sends it once when
     * it connects (configuration, which the master answered itself) and again only when an option changes in play; it does NOT resend it for the
     * reconfiguration after StartConfiguration (ClientPacketListener.broadcastClientInformation). The backend's new player would keep
     * ClientInformation.createDefault() (view distance 2 = about 3 chunks), so the master replays it as the first configuration frame.
     */
    private var clientInfoPayload: ByteArray? = null
    private var relayId: Long? = null
    /** A backend switch in progress: the new backend's configuration is being completed by the master (see [Switch.kt]). */
    private class Switch(val id: Long, val nodeId: String) {
        val cfg = ConfigVirtualClient(); var inPlay = false; val startedNanos = System.nanoTime()
        /** Seamless transplant: the new backend's Login was consumed and its stream is buffered until the client can take over. */
        var login: net.fissionmc.master.seamless.SpawnInfo? = null
        val buffer = ArrayList<ByteArray>(); var gotPosition = false; var timeout: ScheduledFuture<*>? = null
        /** Position hand-off: the first chunk batch is complete, the master told the new backend where the client really is (PlayerMoveTo), and its answering PlayerPosition arrived. */
        var batchDone = false; var sawBatch = false; var moveSent = false; var freshPosition = false
        /** The EXACT position PlayerMoveTo put the new backend's player at; the confirming AcceptTeleportation carries the same numbers (the server snaps to its own, then moves by the difference). */
        var handoff: net.fissionmc.master.seamless.PositionTracker.Pos? = null
        /** First entry (limbo): the backend's Login arrived before the limbo client said it is loaded; it and the frames behind it (in [buffer]) wait for [releaseHeld]. */
        var heldLogin: ByteArray? = null
    }
    private var switching: Switch? = null
    /** `/fission debug switch`: an action bar line on every backend switch (admins only, off by default). */
    @Volatile var debugSwitch = false
    private var switchReason = "command"; private var switchT0 = 0L
    private fun debugSwitchLine(from: String?, to: String, kind: String) {
        if (!debugSwitch) return
        val ms = (System.nanoTime() - switchT0) / 1_000_000
        val c = Component.text("Switched ${from ?: "limbo"} -> $to (${ms} ms, $switchReason, $kind, ${lastDimension} ${lastX},${lastZ})", net.kyori.adventure.text.format.NamedTextColor.YELLOW)
        if (mode == Mode.RELAY && tracker.serverState == ConnectionState.PLAY) writeRelay(Limbo.encode(com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage(true, c)))
    }
    // ---- seamless switching (no Login/Respawn between same-level backends)
    private val idMap = net.fissionmc.master.seamless.EntityIdMap()
    private val clientView = net.fissionmc.master.seamless.ClientView(idMap)
    private val rewriter = net.fissionmc.master.seamless.EntityRewriter(idMap,
        net.fissionmc.master.seamless.PeMetadataCodec { f -> runCatching { EventCreationUtil.createSendEvent(channel, user, null, io.netty.buffer.Unpooled.wrappedBuffer(f), false) }.getOrNull() }) { server.tlog("[client $playerName] $it") }
    /** What the backend the client is on told it about its level (null in the limbo): a switch to a backend with the same level can skip the Login. */
    private var currentSpawn: net.fissionmc.master.seamless.SpawnInfo? = null
    /** Where the client really is (its movement packets); used to hand the new backend the FRESH position (the player data it loads is as old as the start of the switch). */
    private val posTracker = net.fissionmc.master.seamless.PositionTracker()
    private val freshPosition: Boolean get() = server.settings.switchFreshPosition
    /** Login+Respawn fallback: the next PlayerPosition frames of the new backend are rewritten to the client's real position and momentum. */
    private var patchPositions = 0
    /** The position sent to the new backend with PlayerMoveTo on the Login path; the patched PlayerPosition frames carry the same numbers, so the client's acknowledgement matches what the backend expects. */
    private var patchPos: net.fissionmc.master.seamless.PositionTracker.Pos? = null
    /** Keys, sprint, glide, flying and hotbar slot the client reported; replayed to the new backend after a transplant. */
    private val inputState = net.fissionmc.master.seamless.ClientInputState()
    private val pPlayerPosition by lazy { net.fissionmc.master.seamless.PlayPacketIds.c("PlayerPosition") }
    private val seamless: Boolean get() = server.settings.switchMode == "seamless"
    /** ChunkBatchFinished frames forwarded whose ChunkBatchReceived the client has not sent yet; after a transplant that many acks still belong to the OLD backend and are swallowed. */
    private var batchOutstanding = 0; private var staleBatchAcks = 0
    private val pBatchFinished by lazy { net.fissionmc.master.seamless.PlayPacketIds.c("ChunkBatchFinished") }
    private val pBatchAck by lazy { net.fissionmc.master.seamless.PlayPacketIds.s("ChunkBatchReceived") }
    private var graceTask: ScheduledFuture<*>? = null
    /** Waiting for the old backend to save the player before the new one is attached. */
    private var switchPending = false
    /** The network join line was sent (once per login: moving between backends, or back from the limbo, is not a join). */
    private var announcedJoin = false

    private fun announceArrival(backend: String?, fromBackend: String?) {
        if (!announcedJoin) { announcedJoin = true; server.announce(server.settings.announcements.join(playerName, backend)) }
        else if (fromBackend != null && backend != null) server.announce(server.settings.announcements.switched(playerName, backend))
    }
    private val cleanup = SwitchCleanup({ f -> runCatching { EventCreationUtil.createSendEvent(channel, user, null, io.netty.buffer.Unpooled.wrappedBuffer(f), false) }.getOrNull() }, { server.tlog("[client $playerName] $it") })
    /** Keepalive ids the CURRENT backend sent and the client has not answered yet (a stale answer for a previous backend must not reach this one). */
    private val backendKeepAlives = java.util.ArrayDeque<Long>()
    /** Entity id the current Login gave the client (informational: the scripted test asserts it follows the backend's Login). */
    @Volatile var selfEntityId = -1; private set
    /** Backend this client is currently relayed to (null in limbo). */
    @Volatile var currentNodeId: String? = null; private set
    @Volatile var chatGlobal = server.settings.chat.defaultGlobal
    /** Permission level the current backend reports for this player (see [net.fissionmc.protocol.PlayerPermissionReport]). */
    @Volatile var backendLevel = 0; private set
    override fun backendPermission(level: Int) { Trace.emit("permission.backend") { player(playerName, uuid); str("backend", currentNodeId); int("level", level); int("before", backendLevel) }; backendLevel = level }

    // ---- central tab list, ghost players, cross-backend commands (see .dev/docs/tablist-ghosts-commands.md)
    /** The backend the client plays on with a complete PLAY phase on both sides, else null (limbo, classic reconfiguration, no backend). Read by other sessions' threads. */
    @Volatile private var liveNode: String? = null
    private fun refreshLive() { liveNode = if (mode == Mode.RELAY && tracker.clientState == ConnectionState.PLAY && tracker.serverState == ConnectionState.PLAY) currentNodeId else null }
    override val tabUuid: java.util.UUID get() = prof!!.uuid
    override val tabNode: String? get() = liveNode
    override fun tabSend(frame: ByteArray) { channel.eventLoop().execute { if (liveNode != null && channel.isActive) { cleanup.observe(frame); writeRelay(frame) } } }
    override val ghostUuid: java.util.UUID get() = prof!!.uuid
    override val ghostNode: String? get() = liveNode
    override fun ghostSend(frame: ByteArray) { channel.eventLoop().execute { if (liveNode != null && channel.isActive) { cleanup.observe(frame); clientView.observe(frame); writeRelay(frame) } } }   // tab lines and the team are removed again by a transplant's cleanup
    override fun ghostAdded(clientId: Int) { channel.eventLoop().execute { idMap.onAdded(clientId, -1) } }
    override fun ghostRemoved(clientId: Int) { channel.eventLoop().execute { idMap.onRemoved(clientId) } }
    private val tabDecode: (ByteArray) -> com.github.retrooper.packetevents.event.PacketSendEvent? = { f -> runCatching { EventCreationUtil.createSendEvent(channel, user, null, io.netty.buffer.Unpooled.wrappedBuffer(f), false) }.getOrNull() }
    /** The client holds a fresh level/tab list: forget what the hubs think it has. */
    private fun hubsReset() { server.tabs.reset(this); server.ghosts.reset(this) }
    /** The join stream of the current backend is in: add the remote tab lines, then the ghosts that are in range. */
    private fun hubsPlaced() { refreshLive(); server.tabs.placed(this); server.ghosts.placed(this) }

    private class PendingCheck(val seq: Long, val frame: ByteArray, val cmd: String, val targetName: String, val targetNode: String) { var timeout: ScheduledFuture<*>? = null }
    private var pendingCheck: PendingCheck? = null
    private var checkSeq = 0L
    /** The command to run on the new backend once the switch that was started for it is complete (frame + text). */
    private var commandAfterSwitch: PendingCheck? = null
    /** The probe that waits until the new backend has the player (and tells whether it may run the command there). */
    private var arrival: PendingCheck? = null
    private var arrivalTries = 0

    override fun commandChecked(seq: Long, allowed: Boolean, present: Boolean) { channel.eventLoop().execute {
        val ar = arrival
        if (ar != null && ar.seq == seq) { arrivalAnswer(ar, allowed, present); return@execute }
        val pc = pendingCheck ?: return@execute
        if (pc.seq != seq) return@execute
        pc.timeout?.cancel(false); pendingCheck = null
        if (!allowed) { server.tlog("[cross] $playerName: '/${pc.cmd}' not allowed by backend $currentNodeId, passing it on so the backend prints its own error"); passCommand(pc.frame); return@execute }
        val target = server.findByName(pc.targetName)?.liveNodeOf()
        if (target == null || target == currentNodeId) { server.tlog("[cross] $playerName: '/${pc.cmd}': ${pc.targetName} is no longer on another backend (now ${target ?: "offline"}), passing it on"); passCommand(pc.frame); return@execute }
        commandAfterSwitch = pc
        server.tlog("[cross] $playerName: '/${pc.cmd}' allowed by $currentNodeId, moving to $target (${pc.targetName}'s backend), the command is run there on arrival")
        startSwitch(target) { why -> commandAfterSwitch = null; server.tlog("[cross] $playerName: cannot move to $target: $why, passing '/${pc.cmd}' to $currentNodeId"); passCommand(pc.frame) }
    } }

    /** Sends a client command frame to the current backend as is (what the client sent). */
    private fun passCommand(frame: ByteArray) { val id = relayId ?: return; server.relay?.toBackend(id, frame) }

    /**
     * The switch is complete: run the command that triggered it as soon as the new backend HAS the player. Instead of a fixed delay the backend is asked
     * (CommandCheck, answered on its server thread) until it answers "present"; the answer also says whether the player may run the command there.
     */
    private fun runCommandAfterSwitch() {
        val pc = commandAfterSwitch ?: return; commandAfterSwitch = null
        arrival = pc; arrivalTries = 0
        server.tlog("[cross] $playerName arrived on $currentNodeId, waiting for it to have the player before running '/${pc.cmd}'")
        probeArrival(pc)
    }
    private fun probeArrival(pc: PendingCheck) {
        if (arrival == null) return
        val id = relayId
        if (!channel.isActive || mode != Mode.RELAY || id == null) { arrival = null; server.tlog("[cross] $playerName: gone before '/${pc.cmd}' could run"); return }
        val seq = ++checkSeq
        val probe = PendingCheck(seq, pc.frame, pc.cmd, pc.targetName, pc.targetNode); arrival = probe
        probe.timeout = channel.eventLoop().schedule({ if (arrival === probe) retryArrival(probe, "no answer") }, 400, TimeUnit.MILLISECONDS)
        server.relay?.sendTo(id, net.fissionmc.protocol.CommandCheck(id, seq, pc.cmd))
    }
    private fun retryArrival(pc: PendingCheck, why: String) {
        if (arrival !== pc) return
        if (++arrivalTries >= 15) { arrival = null; server.tlog("[cross] $playerName: $why after $arrivalTries tries on $currentNodeId, running '/${pc.cmd}' anyway"); passCommand(pc.frame); return }
        channel.eventLoop().schedule({ if (arrival === pc) probeArrival(pc) }, 150, TimeUnit.MILLISECONDS)
    }
    private fun arrivalAnswer(pc: PendingCheck, allowed: Boolean, present: Boolean) {
        pc.timeout?.cancel(false)
        if (!present) { retryArrival(pc, "the backend does not have the player yet"); return }
        arrival = null
        server.tlog("[cross] $playerName on $currentNodeId (${arrivalTries} retries): '/${pc.cmd}' ${if (allowed) "allowed, running it as the player" else "NOT allowed for the player on this backend (its operator level/permissions here differ from the backend it came from?), running it anyway so the backend prints its error"}")
        passCommand(pc.frame)
    }

    /** `/tp <name>` and friends: true = swallowed (the master asks the sender's backend first and decides). */
    private fun crossCommand(bytes: ByteArray, cmd: String): Boolean {
        val cc = server.crossCommands; val relay = server.relay ?: return false
        if (!cc.enabled) return false
        val insp = cc.inspect(cmd, currentNodeId) { name -> server.findByName(name)?.let { o -> o.liveNodeOf()?.let { n -> o.playerName to n } } }
        Trace.emit("cross.inspect") { player(playerName, uuid); str("cmd", cmd); str("on", currentNodeId); bool("match", insp.match != null); str("reason", insp.reason); str("target", insp.match?.targetName); str("targetNode", insp.match?.targetNode) }
        if (insp.match == null) { if (insp.reason != null) server.tlog("[cross] $playerName: '/$cmd' stays on $currentNodeId: ${insp.reason}"); return false }
        val busy = when { pendingCheck != null -> "another check is pending"; arrival != null || commandAfterSwitch != null -> "a cross-backend command is still arriving"; switching != null || switchPending -> "a switch is running"; mode != Mode.RELAY -> "mode $mode"; liveNode == null -> "the session is not live"; else -> null }
        if (busy != null) { Trace.emit("cross.busy") { player(playerName, uuid); str("cmd", cmd); str("why", busy) }; server.tlog("[cross] $playerName: '/$cmd' stays on $currentNodeId: $busy"); return false }
        val id = relayId ?: return false
        val m = insp.match
        val pc = PendingCheck(++checkSeq, bytes, cmd, m.targetName, m.targetNode); pendingCheck = pc
        pc.timeout = channel.eventLoop().schedule({ if (pendingCheck === pc) { server.tlog("[cross] $playerName: ${currentNodeId} did not answer the check for '/$cmd' in ${server.settings.cross.checkTimeoutMs} ms"); commandChecked(pc.seq, false) } }, server.settings.cross.checkTimeoutMs, TimeUnit.MILLISECONDS)
        server.tlog("[cross] $playerName on $currentNodeId: intercepted '/$cmd' -> ${m.targetName} is on ${m.targetNode}, asking $currentNodeId whether the player may run it")
        relay.sendTo(id, net.fissionmc.protocol.CommandCheck(id, pc.seq, cmd))
        return true
    }
    /** Node of this session as other sessions may see it. */
    fun liveNodeOf(): String? = liveNode
    /**
     * Admin for the master's own commands and command tree: operator level 4 from the master's permission groups (`permissions:` in fission.yml, by default
     * the node fission.admin), or operator level 4 on the backend the player is on right now (`/op` on that backend). Lower levels do not get the master's commands.
     */
    val isAdmin: Boolean get() = server.settings.isAdmin(uuid) || (server.settings.permissions.backendOpsAreAdmins && backendLevel >= 4)

    // ---- static regions: a player that walks into another backend's region is moved there
    @Volatile var lastDimension: String = ""; @Volatile var lastX = 0; @Volatile var lastZ = 0
    private val regionPolicy by lazy { net.fissionmc.master.region.RegionSwitchPolicy(server.settings.regionMargin) }

    override fun playerMoved(dimension: String, x: Int, z: Int) { channel.eventLoop().execute {
        lastDimension = dimension; lastX = x; lastZ = z
        checkRegion(dimension, x, z)
    } }

    private fun exactRegionCheck() {
        if (lastDimension.isEmpty()) return
        val p = posTracker.latest(0.0) ?: return
        checkRegion(lastDimension, Math.floor(p.x).toInt(), Math.floor(p.z).toInt())
    }

    private fun checkRegion(dimension: String, x: Int, z: Int) {
        val regions = server.relay?.regions ?: return
        if (!regions.enabled || mode != Mode.RELAY || switching != null || switchPending) return
        val cur = currentNodeId ?: return
        val now = System.nanoTime()
        val target = regionPolicy.decide(regions, dimension, x, z, cur, now)
        if (target == null) return
        Trace.emit("region.decision") { player(playerName, uuid); str("dim", dimension); int("x", x); int("z", z); str("on", cur); str("owner", regions.owner(dimension, x, z)); str("target", target) }
        if (server.relay?.liveNodeIds()?.contains(target) != true) { Trace.emit("region.target.offline") { player(playerName, uuid); str("target", target) }; return }
        regionPolicy.switched(cur, now)
        server.tlog("[regions] $playerName at $dimension $x,$z leaves $cur for $target")
        startSwitch(target, "region") { why -> server.tlog("[regions] $playerName: cannot move to $target: $why") }
    }

    /** The master's permissions changed: tell the player's backend (it resends the command tree, which the master patches again). */
    fun pushPermission() { channel.eventLoop().execute {
        val id = relayId ?: return@execute; val u = uuid ?: return@execute
        Trace.emit("permission.push") { player(playerName, u); str("backend", currentNodeId); int("level", server.settings.permissions.levelOf(u)); strs("nodes", server.settings.permissions.nodesOf(u)) }
        server.relay?.sendTo(id, net.fissionmc.protocol.PlayerPermission(id, u.mostSignificantBits, u.leastSignificantBits, server.settings.permissions.levelOf(u), server.settings.permissions.nodesOf(u).toList()))
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
        // client.timeout-seconds: a connection that sends nothing, or does not get through the login, must not stay open for ever
        val connectedAt = System.nanoTime(); lastRead = connectedAt
        idleTask = channel.eventLoop().scheduleWithFixedDelay({
            val limit = TimeUnit.SECONDS.toNanos(server.config.timeoutSeconds.toLong()); val now = System.nanoTime()
            if (now - lastRead > limit) { server.tlog("[client ${channel.remoteAddress()}] ${playerName.ifEmpty { "connection" }} sent nothing for ${server.config.timeoutSeconds} s: closing"); if (prof != null) disconnect("Timed out") else channel.close() }
            else if (prof == null && now - connectedAt > limit) channel.close()   // never logged in
        }, 5, 5, TimeUnit.SECONDS)
        wallTask = channel.eventLoop().scheduleWithFixedDelay({ try { borderWall() } catch (t: Throwable) { server.tlog("[client $playerName] border particles: $t") } }, 1, 50, TimeUnit.MILLISECONDS)
        ctx.fireChannelActive()
    }
    private var lastRead = 0L
    private var wallTask: ScheduledFuture<*>? = null
    private var wallTicks = 0

    private fun borderWall() {
        val cfg = server.settings.borderParticles
        if (!cfg.enabled) return
        if (++wallTicks % cfg.intervalTicks != 0) return
        if (liveNode == null || switching != null || switchPending || patchPositions > 0 || !channel.isActive || !channel.isWritable) return
        val regions = server.relay?.regions ?: return
        if (!regions.enabled) return
        if (cfg.respectClientSetting && clientInfoPayload?.let { net.fissionmc.master.region.BorderParticles.particleStatus(it) == 2 } == true) return
        val edges = regions.edges(lastDimension)
        if (edges.isEmpty()) return
        val p = posTracker.latest(0.0) ?: return
        val points = net.fissionmc.master.region.BorderParticles.sample(edges, p.x, p.y, p.z, cfg)
        if (points.isEmpty()) return
        for (pt in points) writeRelay(net.fissionmc.master.region.BorderParticles.frame(pt, cfg))
        Trace.emit("border.particles") { player(playerName, uuid); int("points", points.size); str("dim", lastDimension) }
    }
    private var idleTask: ScheduledFuture<*>? = null

    override fun channelInactive(ctx: ChannelHandlerContext) {
        keepAlive?.cancel(false); idleTask?.cancel(false); wallTask?.cancel(false); server.admission.remove(this)
        pendingCheck?.timeout?.cancel(false); pendingCheck = null
        if (registered) { liveNode = null; server.ghosts.unregister(this); server.tabs.unregister(this) }
        // where the player was: its next login starts on the backend that owns that spot (regions) instead of on any backend followed by a second move
        if (registered && lastDimension.isNotEmpty()) uuid?.let { u -> server.relay?.playerData?.savePosition(u, lastDimension, lastX, lastZ) }
        if (announcedJoin && registered) { announcedJoin = false; server.unregister(this); registered = false; server.announce(server.settings.announcements.leave(playerName, currentNodeId)) }
        relayId?.let { server.relay?.detach(it, "client left") }
        switching?.let { server.relay?.detach(it.id, "client left") }
        if (registered) server.unregister(this)
        afterClose?.let { afterClose = null; it() }
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

    private val pAddEntity by lazy { net.fissionmc.master.seamless.PlayPacketIds.c("AddEntity") }

    private fun realEntityArrived(frame: ByteArray) {
        val b = io.netty.buffer.Unpooled.wrappedBuffer(frame)
        VarInts.tryRead(b); VarInts.tryRead(b)
        if (b.readableBytes() < 16) return
        val uuid = java.util.UUID(b.readLong(), b.readLong())
        val ghost = server.ghosts.realArrived(this, uuid) ?: return
        val remove = net.fissionmc.master.seamless.GhostFrames.remove(ghost)
        cleanup.observe(remove); clientView.observe(remove); writeRelay(remove); idMap.onRemoved(ghost)
    }

    private fun deliverRelay(rawFrame: ByteArray) {
        if (mode != Mode.RELAY && mode != Mode.AWAIT_ACK) return
        // A frame whose id does not exist in the phase the client is in would make the vanilla client drop the connection
        // ("Received unknown packet id N" in IdDispatchCodec). Vanilla's server cannot produce one (its outbound protocol is switched
        // together with the client's: ServerGamePacketListenerImpl.switchToConfig / handleConfigurationFinished); a backend that does
        // is out of step, so say so loudly and keep it away from the client.
        val state = tracker.serverState; val id = tracker.packetId(rawFrame)
        if (PacketType.getById(com.github.retrooper.packetevents.protocol.PacketSide.SERVER, state, ClientVersion.V_26_3, id) == null) {
            server.tlog("[client $playerName] DROPPED backend frame id $id: not a clientbound $state packet (client phase=$state)")
            Trace.emit("pkt.s2c.drop") { player(playerName, uuid); int("id", id); str("state", state.name); str("why", "not a clientbound packet of this phase") }
            return
        }
        // seamless switching: entity ids of the current backend -> the client's id space (identity until the first transplant)
        if (Trace.on) idMap.traceN = 0
        val rewritten = if (seamless && state == ConnectionState.PLAY) (rewriter.toClient(rawFrame) ?: run { Trace.emit("pkt.s2c.drop") { player(playerName, uuid); int("id", id); str("name", pktName(true, state, id)); str("why", "rewriter: unknown entity or dropped"); int("size", rawFrame.size) }; return }) else rawFrame
        var patched = rewritten
        if (patchPositions > 0 && state == ConnectionState.PLAY && id == pPlayerPosition) {
            patchPositions--
            val p = patchPos ?: posTracker.latest(2.0)
            if (p != null) patched = net.fissionmc.master.seamless.PositionFrames.patchPlayerPosition(rewritten, p) else net.fissionmc.master.seamless.PositionFrames.observeTeleport(rewritten, posTracker)
        } else if (state == ConnectionState.PLAY && id == pPlayerPosition) net.fissionmc.master.seamless.PositionFrames.observeTeleport(rewritten, posTracker)   // the client was teleported (spawn, /tp, a switch): that is where it is now
        var frame = toClient(patched) ?: run { Trace.emit("pkt.s2c.drop") { player(playerName, uuid); int("id", id); str("name", pktName(true, state, id)); str("why", "packet bus listener") }; return }
        Trace.emit("pkt.s2c") {
            player(playerName, uuid); str("backend", currentNodeId); int("id", id); str("name", pktName(true, state, id)); int("size", rawFrame.size); int("crc", TraceEvent.crc(rawFrame))
            if (!frame.contentEquals(rawFrame)) { int("sizeOut", frame.size); int("crcOut", TraceEvent.crc(frame)) }
            if (idMap.traceN > 0) ints("entityIds", idMap.traceList())   // pairs: backend id, client id
            str("mode", mode.name)
        }
        if (state == ConnectionState.PLAY) {
            join?.let { j -> if (id == pPlayerPosition) j.positionSent() else if (id == pBatchFinished) j.chunkBatch() }
            // central tab list: the hub records this player's own line and keeps the lines of players who are still online on another backend
            if (id == PlayIds.playerInfoUpdate) frame = server.tabs.update(this, frame)
            else if (id == PlayIds.playerInfoRemove) frame = server.tabs.remove(this, frame) ?: return
            cleanup.observe(frame); clientView.observe(frame)
            if (id == pBatchFinished) batchOutstanding++
            if (id == PlayIds.keepAliveS2C && frame.size >= 9) { backendKeepAlives.add(java.nio.ByteBuffer.wrap(PlayIds.payload(frame)).long); while (backendKeepAlives.size > 8) backendKeepAlives.poll() }
            if (id == PlayIds.commands) { val patched = ChatCommandTree.patch(frame, isAdmin, server.log); if (patched != null) { writeRelay(patched); return } }
        }
        when (mode) {
            Mode.RELAY -> { writeRelay(frame); if (state == ConnectionState.PLAY && id == pAddEntity) realEntityArrived(frame) }
            else -> if (pendingFromBackend.size < 20_000) pendingFromBackend.add(frame) else channel.close()
        }
        // classic reconfiguration (fallback path): the backend's Login is the first PLAY frame; the hubs add their lines behind it
        if (mode == Mode.RELAY && state == ConnectionState.PLAY && id == PlayIds.login && liveNode == null) { hubsPlaced(); runCommandAfterSwitch() }
    }

    private fun pktName(clientbound: Boolean, state: ConnectionState, id: Int): String =
        if (state != ConnectionState.PLAY) "${state.name.lowercase()}:$id" else if (clientbound) net.fissionmc.master.seamless.PlayPacketIds.nameC(id) else net.fissionmc.master.seamless.PlayPacketIds.nameS(id)

    private fun writeRelay(frame: ByteArray) {
        if (!channel.isActive) return
        channel.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(frame))
        if (channel.bytesBeforeUnwritable() == 0L) overflow++ else overflow = 0
        // a client that does not read: dropped after 20,000 frames or 64 MiB waiting for it, whichever comes first (the master must not buffer without limit)
        if (overflow > 20_000 || channel.bytesBeforeWritable() > 64L * 1024 * 1024) { server.tlog("[client $playerName] too slow (${channel.bytesBeforeWritable() shr 20} MiB waiting), dropping"); channel.close() }
    }
    private var overflow = 0

    override fun detached(reason: String, backendLost: Boolean) = detachedFrom(relayId ?: -1L, reason, backendLost)

    override fun detachedFrom(bindingId: Long, reason: String, backendLost: Boolean) { channel.eventLoop().execute {
        val sw = switching
        if (sw != null && sw.id == bindingId) {   // the backend we were switching to refused or died: the player stays where it is
            sw.timeout?.cancel(false); switching = null; restoreDataOwner(); commandAfterSwitch = null
            server.tlog("[client $playerName] switch to ${sw.nodeId} failed: $reason")
            tell("Could not move to ${sw.nodeId}: ${reason.removePrefix("kick:")}")
            return@execute
        }
        if (bindingId != relayId) return@execute
        val pendingTransplant = switching?.takeIf { it.login != null }
        if (pendingTransplant != null) { server.tlog("[client $playerName] backend ended during a transplant to ${pendingTransplant.nodeId}: taking over now"); completeTransplant(pendingTransplant); return@execute }
        relayId = null; currentNodeId = null; backendLevel = 0; refreshLive()
        server.tlog("[client $playerName] backend session ended: $reason")
        if (mode != Mode.RELAY && mode != Mode.AWAIT_ACK) { channel.close(); return@execute }
        // the client's real phase is what the tracker saw, not what we last set by hand
        user.decoderState = tracker.clientState; user.encoderState = tracker.serverState
        when {
            // the backend died while the client plays: back to the limbo in the PLAY state (no Reconfiguring screen), or on to the backend we are switching to
            backendLost && mode == Mode.RELAY && tracker.clientState == ConnectionState.PLAY && tracker.serverState == ConnectionState.PLAY ->
                if (switching == null) toLimboSeamless() else server.tlog("[client $playerName] waiting for the switch to ${switching!!.nodeId}")
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
        server.tlog("[client] $playerName: backend lost, back to limbo")
        pendingFromBackend.clear(); tap = null; tracker = net.fissionmc.packets.ClientStateTracker(net.fissionmc.packets.StateIds.forVersion(), ConnectionState.PLAY)
        mode = Mode.LIMBO; currentSpawn = null; idMap.start(Limbo.ENTITY_ID); clientView.reset()
        refreshLive(); server.tabs.hidden(this); server.ghosts.hidden(this); hubsReset()
        startLimboPlay(cleanup.cleanupFrames())
    }

    /** Moves a limbo player to a backend (if one is alive), see [startSwitch]. */
    fun tryAttach() { channel.eventLoop().execute { if (mode == Mode.LIMBO && server.relay != null) server.admission.enqueue(this) } }

    /** The queue let this limbo player in. */
    fun admitFromQueue() { channel.eventLoop().execute { if (mode == Mode.LIMBO) startSwitch(null, "limbo") } }

    /** Tells a waiting limbo player its place, or that every server is down. */
    fun waitingNotice(place: Int, backendsUp: Boolean) { channel.eventLoop().execute {
        if (!channel.isActive || mode != Mode.LIMBO || !clientLoaded || user.decoderState != ConnectionState.PLAY) return@execute
        val text = if (!backendsUp) Component.text("All servers are down. You will join as soon as one is back.", net.kyori.adventure.text.format.NamedTextColor.RED)
            else Component.text("Joining the server, you are number $place in the queue.", net.kyori.adventure.text.format.NamedTextColor.YELLOW)
        channel.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(Limbo.encode(com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage(true, text))))
    } }   // timers and backend joins only ever move LIMBO players

    /**
     * Moves the client to [nodeId] (round robin when null) without leaving the PLAY state. The new backend starts its CONFIGURATION with
     * the master as its client ([ConfigVirtualClient]); its Login completes the switch ([completeSwitch]). If the backend's configuration
     * differs from the one the client got, the old StartConfiguration path is used for this switch ([classicAttach]).
     * [report] receives a short reason when nothing could be started.
     */
    fun startSwitch(nodeId: String?, reason: String = "command", report: (String) -> Unit = {}) { channel.eventLoop().execute {
        if (!channel.isActive) return@execute
        if (mode == Mode.LIMBO && user.decoderState != ConnectionState.PLAY) return@execute
        if (switching != null || switchPending) { Trace.emit("switch.refused") { player(playerName, uuid); str("to", nodeId); str("reason", reason); str("why", "a switch is already running") }; report("a switch is already running"); return@execute }
        if (mode == Mode.LIMBO) {
            // The backend's configuration starts at once (it takes 0.2-1 s and overlaps with the client loading the limbo); only its Login waits until the limbo client said it is
            // loaded (the real-client failure happened when StartConfiguration followed the limbo burst immediately), or the grace period is over: see onSwitchFrame / releaseHeld.
        } else if (mode != Mode.RELAY || tracker.clientState != ConnectionState.PLAY || tracker.serverState != ConnectionState.PLAY) { Trace.emit("switch.refused") { player(playerName, uuid); str("to", nodeId); str("reason", reason); str("why", "busy"); str("mode", mode.name); str("clientState", tracker.clientState.name); str("serverState", tracker.serverState.name) }; report("the player is busy"); return@execute }
        if (nodeId != null && nodeId == currentNodeId) { report("already on $nodeId"); return@execute }
        Trace.emit("switch.start") { player(playerName, uuid); str("from", currentNodeId); str("to", nodeId); str("reason", reason); str("mode", mode.name) }
        switchReason = if (mode == Mode.LIMBO) "limbo" else reason; switchT0 = System.nanoTime()
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

    /** First entry with regions: the backend that owns the spot the player was at when it left (remembered by the master), if it is connected. */
    private fun preferredNode(): String? {
        val relay = server.relay ?: return null; val u = uuid ?: return null
        if (mode != Mode.LIMBO || !relay.regions.enabled) return null
        val pos = relay.playerData?.readPosition(u) ?: return null
        return relay.regions.owner(pos.first, pos.second, pos.third)?.takeIf { it in relay.liveNodeIds() }
    }

    /** The backend accepted the player but its Login never came (it hangs, or a plugin blocks the pre-login): give the switch up so the player is not stuck with it. */
    private fun switchTimedOut(sw: Switch) {
        if (switching !== sw || sw.login != null || sw.heldLogin != null) return
        Trace.emit("switch.timeout") { player(playerName, uuid); str("to", sw.nodeId); long("ms", server.settings.switchTimeoutMs) }
        server.tlog("[client $playerName] switch to ${sw.nodeId}: no Login within ${server.settings.switchTimeoutMs} ms (switch.timeout-ms), giving it up")
        switching = null; server.relay?.detach(sw.id, "switch timed out"); restoreDataOwner()
        commandAfterSwitch?.let { server.tlog("[cross] $playerName: '/${it.cmd}' dropped, the switch to ${sw.nodeId} timed out"); commandAfterSwitch = null }
        if (mode == Mode.RELAY) tell("Could not move to ${sw.nodeId}: it did not answer in time")
        else channel.eventLoop().schedule({ tryAttach() }, 2, TimeUnit.SECONDS)   // limbo: try again (round robin picks the next backend)
    }

    private fun attachSwitch(nodeId0: String?, report: (String) -> Unit) {
        val preferred = if (nodeId0 == null) preferredNode() else null
        val nodeId = nodeId0 ?: preferred
        val id = server.relay?.attach(this, nodeId) ?: (if (nodeId0 == null && preferred != null) server.relay?.attach(this, null) else null)
        if (id == null) { Trace.emit("switch.attach.failed") { player(playerName, uuid); str("to", nodeId) }; report(if (nodeId == null) "no backend is alive" else "backend $nodeId is not connected"); return }
        val node = server.relay!!.nodeIdOf(id) ?: "?"
        Trace.emit("switch.attach") { player(playerName, uuid); str("to", node); long("binding", id) }
        val sw = Switch(id, node); switching = sw; join?.attached()
        channel.eventLoop().schedule({ switchTimedOut(sw) }, server.settings.switchTimeoutMs, TimeUnit.MILLISECONDS)
        // the client's real options go first, like in the first configuration of a fresh vanilla connection
        clientInfoPayload?.let { p -> server.relay?.toBackend(id, PlayIds.frame(0, p)) }
        server.tlog("[client] $playerName -> backend $node (player $id), seamless")
    }

    /** A failed switch leaves the player where it is: the data owner goes back to that backend. */
    private fun restoreDataOwner() { uuid?.let { server.relay?.setDataOwner(it, currentNodeId); server.relay?.dropPendingEntities(it) } }

    private fun onSwitchFrame(sw: Switch, frame: ByteArray) {
        if (!sw.inPlay) {
            val r = sw.cfg.onFrame(frame)
            r.replies.forEach { server.relay?.toBackend(sw.id, it) }
            if (r.abort != null) {
                Trace.emit("switch.abort") { player(playerName, uuid); str("to", sw.nodeId); str("why", r.abort); bool("mismatch", r.mismatch) }
                server.tlog("[client $playerName] switch to ${sw.nodeId}: ${r.abort}")
                switching = null; server.relay?.detach(sw.id, "switch aborted"); restoreDataOwner()
                if (r.mismatch) { server.tlog("[client $playerName] falling back to StartConfiguration for ${sw.nodeId}"); classicAttach(sw.nodeId) }
                else { tell("Could not move to ${sw.nodeId}: ${r.abort}"); commandAfterSwitch?.let { server.tlog("[cross] $playerName: '/${it.cmd}' dropped, the switch to ${sw.nodeId} was aborted"); commandAfterSwitch = null } }
                return
            }
            if (r.finished) { sw.inPlay = true; join?.configDone(); Trace.emit("switch.config.done") { player(playerName, uuid); str("to", sw.nodeId) } }
            return
        }
        if (sw.heldLogin != null) { sw.buffer += frame; if (sw.buffer.size > 20_000) releaseHeld(sw); return }
        if (sw.login != null) { bufferForTransplant(sw, frame); return }
        if (Limbo.idOf(frame) == PlayIds.login) join?.backendLogin()
        if (Limbo.idOf(frame) != PlayIds.login) { server.tlog("[client $playerName] " + (if (Limbo.idOf(frame) == PlayIds.disconnect) "${sw.nodeId} KICKED the player before its Login: ${frameTexts(frame)}" else "dropped play frame ${Limbo.idOf(frame)} from ${sw.nodeId} before its Login")); return }
        // seamless: the client keeps its level and player when the new backend's level is the same one
        val cur = currentSpawn; val parsed = runCatching { net.fissionmc.master.seamless.SpawnInfo.parse(frame) }.getOrNull()
        val why = when {
            !seamless -> "switch.mode is login"
            mode != Mode.RELAY || relayId == null -> "the client is in the limbo (first entry)"
            cur == null -> "the level of the current backend is unknown"
            parsed == null -> "the Login could not be parsed"
            else -> cur.incompatibility(parsed)
        }
        Trace.emit("switch.login") { player(playerName, uuid); str("to", sw.nodeId); str("path", if (why == null) "transplant" else "login+respawn"); str("why", why); int("size", frame.size) }
        if (why == null && parsed != null) {
            sw.login = parsed
            sw.timeout = channel.eventLoop().schedule({ if (switching === sw) { server.tlog("[client $playerName] transplant to ${sw.nodeId}: no complete first chunk batch within 4 s, taking over with what arrived"); completeTransplant(sw) } }, 4, TimeUnit.SECONDS)
            return
        }
        if (mode == Mode.LIMBO && !clientLoaded && !graceOver) { sw.heldLogin = frame; return }   // the limbo client is still loading: hold the Login (and what follows) back
        server.tlog("[switch] $playerName -> ${sw.nodeId}: Login+Respawn path because $why")
        completeSwitch(sw, frame)
    }

    /** The limbo client is loaded (or the grace period is over): forward the held Login, Respawn and everything the backend sent behind it. */
    private fun releaseHeld(sw: Switch) {
        val login = sw.heldLogin ?: return
        if (switching !== sw) return
        sw.heldLogin = null
        val held = sw.buffer.toList(); sw.buffer.clear()
        server.tlog("[switch] $playerName -> ${sw.nodeId}: Login+Respawn path because the client is in the limbo (first entry); the Login was held until the limbo client was loaded, ${held.size} frames behind it")
        completeSwitch(sw, login)
        held.forEach { deliverRelay(it) }
    }

    /** The new backend's stream after its Login, held back until the first chunk batch is complete (or 4 s). */
    private fun bufferForTransplant(sw: Switch, frame: ByteArray) {
        val id = Limbo.idOf(frame)
        sw.buffer += frame
        if (id == pBatchFinished) sw.sawBatch = true
        if (id == pPlayerPosition) { if (sw.moveSent) sw.freshPosition = true; sw.gotPosition = true }
        if (sw.sawBatch && sw.gotPosition) sw.batchDone = true
        if (sw.buffer.size > 40_000) { completeTransplant(sw); return }
        if (!sw.batchDone) return
        // everything the new backend sends first is here: tell it where the client REALLY is now (the player data it loaded is as old as the start of the switch), as late as possible
        if (!sw.moveSent) {
            sw.moveSent = true
            Trace.emit("switch.transplant.batch") { player(playerName, uuid); str("to", sw.nodeId); int("buffered", sw.buffer.size); bool("freshPosition", sw.freshPosition) }
            // the position the client was at when its last packet passed the master, NOT extrapolated: the backend's player gets exactly this spot (an extrapolated spot can lie inside a wall the client slides along,
            // and a spot that differs from the one the confirmation carries makes the server's movement check teleport the player back); the next real movement packet closes the few ticks of lag
            val p = if (freshPosition) posTracker.latest(0.0) else null
            if (p == null) { completeTransplant(sw); return }
            sw.handoff = p
            val u = uuid
            if (u == null) { completeTransplant(sw); return }
            server.relay?.sendTo(sw.id, net.fissionmc.protocol.PlayerMoveTo(u.mostSignificantBits, u.leastSignificantBits, p.x, p.y, p.z, p.yaw, p.pitch))
            channel.eventLoop().schedule({ if (switching === sw) { server.tlog("[client $playerName] transplant to ${sw.nodeId}: no answer to PlayerMoveTo within 500 ms, taking over"); completeTransplant(sw) } }, 500, TimeUnit.MILLISECONDS)
            return
        }
        if (sw.freshPosition) completeTransplant(sw)
    }

    /**
     * The seamless takeover: no Login, no Respawn, no StartConfiguration. In ONE bundle (the client applies it in one tick): remove what the old backend put on the client,
     * translate the new Login into incremental packets, replay the new backend's join stream in the client's id space; the chunks follow (and the client's chunks the new
     * backend does not cover are forgotten). The new backend gets PlayerLoaded from the master: the client does not send one without a Login.
     */
    private fun completeTransplant(sw: Switch) {
        val login = sw.login ?: return
        if (switching !== sw) return
        sw.timeout?.cancel(false)
        val old = relayId; val before = currentNodeId
        Trace.emit("switch.transplant.begin") { player(playerName, uuid); str("from", before); str("to", sw.nodeId); int("buffered", sw.buffer.size); bool("freshPosition", sw.freshPosition); int("heldEntities", idMap.heldIds().size) }
        val cleanupTab = cleanup.cleanupFrames()
        val removal = net.fissionmc.master.seamless.TransplantBuilder.cleanup(idMap, clientView, cleanupTab)
        val removedEntities = idMap.heldIds().size
        hubsReset()
        relayId = sw.id; currentNodeId = sw.nodeId; switching = null; refreshLive()
        if (old != null) server.relay?.detach(old, "switched to ${sw.nodeId}")
        backendKeepAlives.clear(); pendingFromBackend.clear()
        staleBatchAcks = batchOutstanding; batchOutstanding = 0
        idMap.transplantTo(login.playerId)
        val heldChunks = clientView.chunks.toList(); clientView.objectives.clear(); clientView.teams.clear(); clientView.effects.clear()
        currentSpawn = login
        val chunkish = setOf("LevelChunkWithLight", "LightUpdate", "ChunkBatchStart", "ChunkBatchFinished", "ForgetLevelChunk", "ChunksBiomes").map { net.fissionmc.master.seamless.PlayPacketIds.c(it) }.toSet()
        // the client was never told about the new backend's PlayerPosition: the master confirms the teleport itself with the client's real position (the 26.3 server applies the position
        // of AcceptTeleportation), so the player is not snapped back by speed x switch duration
        val latest = if (sw.freshPosition) sw.handoff else null   // without the backend's answer to PlayerMoveTo its own PlayerPosition (a stale spot) goes to the client, which acknowledges it itself
        val lastTeleportId = if (latest != null) sw.buffer.lastOrNull { Limbo.idOf(it) == pPlayerPosition }?.let { net.fissionmc.master.seamless.PositionFrames.teleportId(it) } else null
        val (chunkFrames, other0) = sw.buffer.partition { Limbo.idOf(it) in chunkish }
        val other = if (lastTeleportId != null) other0.filter { Limbo.idOf(it) != pPlayerPosition } else other0
        val bundled = removal.size + other.size < 3500
        if (bundled) channel.write(io.netty.buffer.Unpooled.wrappedBuffer(net.fissionmc.master.seamless.TransplantBuilder.bundleDelimiter))
        removal.forEach { channel.write(io.netty.buffer.Unpooled.wrappedBuffer(it)); clientView.observe(it) }
        server.ghosts.entityFramesFor(this, sw.nodeId).forEach { channel.write(io.netty.buffer.Unpooled.wrappedBuffer(it)); cleanup.observe(it); clientView.observe(it) }
        net.fissionmc.master.seamless.TransplantBuilder.fromLogin(login, idMap.clientSelf).forEach { channel.write(io.netty.buffer.Unpooled.wrappedBuffer(it)) }
        other.forEach { deliverRelay(it) }
        if (bundled) channel.write(io.netty.buffer.Unpooled.wrappedBuffer(net.fissionmc.master.seamless.TransplantBuilder.bundleDelimiter))
        chunkFrames.forEach { deliverRelay(it) }
        val sentByNew = clientView.chunks.filter { it !in heldChunks.toSet() }.toSet() + chunkFrames.mapNotNull { f -> if (Limbo.idOf(f) == net.fissionmc.master.seamless.PlayPacketIds.c("LevelChunkWithLight")) io.netty.buffer.Unpooled.wrappedBuffer(f).let { b -> VarInts.tryRead(b); val x = b.readInt(); val z = b.readInt(); (x.toLong() and 0xFFFFFFFFL) or ((z.toLong() and 0xFFFFFFFFL) shl 32) } else null }
        val forgets = net.fissionmc.master.seamless.TransplantBuilder.forgetOutside(heldChunks, sentByNew, clientView.centerX, clientView.centerZ, login.chunkRadius)
        forgets.forEach { channel.write(io.netty.buffer.Unpooled.wrappedBuffer(it)); clientView.observe(it) }
        channel.flush()
        if (lastTeleportId != null && latest != null) server.relay?.toBackend(sw.id, net.fissionmc.master.seamless.PositionFrames.acceptTeleportation(lastTeleportId, latest.x, latest.y, latest.z, latest.yaw, latest.pitch))
        server.relay?.toBackend(sw.id, PlayIds.frame(PlayIds.playerLoaded))
        // the new backend knows the client from the saved data only: tell it the keys, sprint, glide, flying and slot the client is in now (the server ignores these before PlayerLoaded)
        val replayed = inputState.replay(login.playerId); replayed.forEach { server.relay?.toBackend(sw.id, it) }
        if (net.fissionmc.net.FissionDebug.on) server.tlog("[client $playerName] transplant input replay: ${replayed.size} frames (sprint ${inputState.sprinting}, input ${inputState.input}, slot ${inputState.slot})")
        hubsPlaced(); runCommandAfterSwitch()
        Trace.emit("switch.transplant.done") { player(playerName, uuid); str("from", before); str("to", sw.nodeId); long("ms", (System.nanoTime() - sw.startedNanos) / 1_000_000); int("removedEntities", removedEntities); int("selfBackend", login.playerId); int("selfClient", idMap.clientSelf); int("chunkFrames", chunkFrames.size) }
        announceArrival(sw.nodeId, if (old != null) before else null); debugSwitchLine(before, sw.nodeId, "transplant")
        server.tlog("[client] $playerName now on ${sw.nodeId} (transplant: no Login/Respawn, client keeps entity id ${idMap.clientSelf}, switch took ${(System.nanoTime() - sw.startedNanos) / 1_000_000} ms, $removedEntities entities removed, ${removal.size} cleanup frames, ${sw.buffer.size} buffered frames, ${forgets.size} chunks forgotten, position hand-off ${if (lastTeleportId != null) "to the client's real position" else "off"})")
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
        hubsReset(); refreshLive()
        cleanupFrames.forEach { channel.write(io.netty.buffer.Unpooled.wrappedBuffer(it)) }
        selfEntityId = if (login.size >= 5) java.nio.ByteBuffer.wrap(PlayIds.payload(login)).int else -1
        idMap.start(selfEntityId); clientView.reset(); batchOutstanding = 0; staleBatchAcks = 0
        if (freshPosition && posTracker.known && posTracker.moved && old != null) {   // Login+Respawn path: the new backend's PlayerPosition frames show the client's real position and momentum, and the backend moves its player there
            patchPositions = 2
            val p = posTracker.latest(1.5); patchPos = p; val u = uuid
            if (p != null && u != null) server.relay?.sendTo(sw.id, net.fissionmc.protocol.PlayerMoveTo(u.mostSignificantBits, u.leastSignificantBits, p.x, p.y, p.z, p.yaw, p.pitch))
        }
        currentSpawn = runCatching { net.fissionmc.master.seamless.SpawnInfo.parse(login) }.getOrNull()
        val out = toClient(login)
        if (out != null) channel.write(io.netty.buffer.Unpooled.wrappedBuffer(out))
        // a second Login keeps the old LocalPlayer (bound to the old level): Respawn makes the client build a new one (see RespawnFrames)
        runCatching { RespawnFrames.fromLogin(login) }.onSuccess { channel.write(io.netty.buffer.Unpooled.wrappedBuffer(it)) }.onFailure { server.tlog("[client $playerName] no Respawn after the Login: $it") }
        channel.flush()
        hubsPlaced(); runCommandAfterSwitch()
        Trace.emit("switch.login.done") { player(playerName, uuid); str("from", currentNodeIdBefore); str("to", sw.nodeId); long("ms", (System.nanoTime() - sw.startedNanos) / 1_000_000); int("selfEntity", selfEntityId) }
        announceArrival(sw.nodeId, if (old != null) currentNodeIdBefore else null); debugSwitchLine(currentNodeIdBefore, sw.nodeId, "login")
        join?.let { j -> j.loginForwarded(); channel.eventLoop().schedule({ if (join === j) { server.tlog(j.line(playerName, sw.nodeId)); join = null } }, 20, TimeUnit.SECONDS) }
        server.tlog("[client] $playerName now on ${sw.nodeId} (Login entity id $selfEntityId, switch took ${(System.nanoTime() - sw.startedNanos) / 1_000_000} ms, ${cleanupFrames.size} cleanup frames)")
    }

    /** The old way: StartConfiguration, the client reconfigures (shows "Reconfiguring"). Used only when the backend's configuration differs. */
    private fun classicAttach(nodeId: String?) {
        val old = relayId
        val id = server.relay?.attach(this, nodeId)
        if (id == null) { tell("Could not move to ${nodeId ?: "a backend"}: not connected"); return }
        if (old != null) server.relay?.detach(old, "switch (classic)")
        relayId = id; currentNodeId = server.relay?.nodeIdOf(id); mode = Mode.AWAIT_ACK; refreshLive(); server.ghosts.hidden(this); currentSpawn = null; clientView.reset(); batchOutstanding = 0; staleBatchAcks = 0
        keepAlive?.cancel(false); backendKeepAlives.clear(); pendingFromBackend.clear()
        tap = net.fissionmc.packets.PacketTap.create(channel, prof?.uuid, prof?.name, user.clientVersion, server.log)
        tracker = tap?.tracker ?: net.fissionmc.packets.ClientStateTracker(net.fissionmc.packets.StateIds.forVersion())
        user.decoderState = ConnectionState.PLAY; user.encoderState = ConnectionState.PLAY
        send(com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerConfigurationStart())
        server.tlog("[client] $playerName -> backend $currentNodeId (player $id), classic StartConfiguration")
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        server.tlog("[client ${channel.remoteAddress()}] ${cause.javaClass.simpleName}: ${cause.message}")
        ctx.close()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val buf = msg as ByteBuf
        lastRead = System.nanoTime()
        if (mode == Mode.RELAY) {   // opaque relay: frame goes to the backend untouched (P3 listeners hook in here)
            try {
                val bytes = ByteArray(buf.readableBytes()); buf.readBytes(bytes)
                val id = relayId
                if (Trace.on) idMap.traceN = 0
                if (tracker.clientState == ConnectionState.PLAY && interceptClient(bytes)) { Trace.emit("pkt.c2s.swallowed") { player(playerName, uuid); int("id", tracker.packetId(bytes)); str("name", pktName(false, tracker.clientState, tracker.packetId(bytes))); int("size", bytes.size) }; return }
                join?.let { j -> if (j.isForwarded && tracker.packetId(bytes) == PlayIds.playerLoaded) { j.playerLoaded(); server.tlog(j.line(playerName, currentNodeId)); join = null } }
                posTracker.observe(bytes); inputState.observe(bytes); exactRegionCheck()
                val tapped = fromClient(bytes)
                val out = if (seamless && tapped != null) rewriter.toBackend(tapped) else tapped
                Trace.emit("pkt.c2s") {
                    val pid = tracker.packetId(bytes)
                    player(playerName, uuid); str("backend", currentNodeId); int("id", pid); str("name", pktName(false, tracker.clientState, pid)); int("size", bytes.size); int("crc", TraceEvent.crc(bytes))
                    if (out == null) str("why", "dropped (packet bus or rewriter)") else if (!out.contentEquals(bytes)) { int("sizeOut", out.size); int("crcOut", TraceEvent.crc(out)) }
                    if (idMap.traceN > 0) ints("entityIds", idMap.traceList())   // pairs: client id, backend id (-1 = unknown)
                }
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
            if (System.getProperty("fission.debug.packets") != null) server.tlog("[client] $username <- $st ${event.packetType}")
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
            pBatchAck -> { if (staleBatchAcks > 0) { staleBatchAcks--; return true }; if (batchOutstanding > 0) batchOutstanding--; return false }
            PlayIds.chatCommand, PlayIds.chatCommandSigned -> {
                val cmd = Strings.readFirst(bytes) ?: return false
                if (pid == PlayIds.chatCommand && crossCommand(bytes, cmd)) return true   // the signed variant carries a signature chain of the sender's backend: never held back
                if (pid == PlayIds.chatCommandSigned && server.crossCommands.inspect(cmd, currentNodeId) { n -> server.findByName(n)?.let { o -> o.liveNodeOf()?.let { x -> o.playerName to x } } }.match != null)
                    server.tlog("[cross] $playerName: '/$cmd' arrived as a SIGNED command packet, which cannot be held back; it goes to $currentNodeId unchanged (the player is not moved)")
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
        channel.writeAndFlush(w.buffer).addListener { f -> if (!f.isSuccess) server.tlog("[client $playerName] write failed: ${f.cause()}") }
    }
    private fun sendAll(ws: List<PacketWrapper<*>>) { ws.forEach { w -> w.prepareForSend(channel, true, false); channel.write(w.buffer) }; channel.flush() }

    fun disconnect(reason: String) {
        if (!channel.isActive) return
        server.tlog("[client ${channel.remoteAddress()}] disconnect: $reason")
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
        server.tlog("[client] handshake protocol=${h.protocolVersion} host=${h.serverAddress}:${h.serverPort} intention=${h.intention}")
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
                server.tlog("[client] $username: verifying with the session server")
                server.authenticator.authenticate(username, hash).whenComplete { p, err ->
                    if (err != null) server.tlog("[client] $username: authentication error: $err")
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
        prof = p; join = JoinTiming()
        if (server.onlinePlayers >= server.config.maxPlayers && !server.settings.isAdmin(p.uuid)) { disconnect("The server is full!"); return }   // client.max-players (admins always get in)
        val older = server.register(this, p)
        registered = true
        // the account is already online: the older connection is ended first (its backend saves the player), then this login goes on, like vanilla's "logged in from another location"
        if (older != null) { server.tlog("[client] ${p.name} logged in again: ending its older connection"); older.endForNewLogin { channel.eventLoop().execute { if (channel.isActive) finishLogin(p) } }; return }
        finishLogin(p)
    }

    /** Another connection logged in with this account: save the player on its backend, then disconnect; [done] runs afterwards (or after 3 s). */
    fun endForNewLogin(done: () -> Unit) { channel.eventLoop().execute {
        if (!channel.isActive) { done(); return@execute }
        afterClose = done   // runs at the end of channelInactive: this session has left the hubs and its backend by then
        val id = relayId; val u = uuid
        val end = { channel.eventLoop().execute { disconnect("You logged in from another location"); channel.eventLoop().schedule({ channel.close() }, 3, TimeUnit.SECONDS); Unit } }
        if (id != null && u != null && server.relay?.playerData != null) server.relay!!.flushPlayerData(id, u, 3000) { end() } else end()
    } }
    private var afterClose: (() -> Unit)? = null

    private fun finishLogin(p: PlayerProfile) {
        server.tabs.register(this, tabDecode); server.ghosts.register(this)
        server.settings.permissions.bindOnLogin(p.name, p.uuid)   // bootstrap: a name entry in permissions.players becomes this uuid
        val t = server.config.compressionThreshold
        if (t >= 0) {
            send(WrapperLoginServerSetCompression(t))
            channel.pipeline().addAfter("prepender", "compress", CompressionEncoder(t))
            channel.pipeline().addAfter("prepender", "decompress", DecompressionDecoder())
        }
        val up = UserProfile(p.uuid, p.name)
        p.properties.forEach { up.textureProperties.add(com.github.retrooper.packetevents.protocol.player.TextureProperty(it.name, it.value, it.signature)) }
        user.profile.uuid = p.uuid; user.profile.name = p.name
        server.tlog("[client] ${p.name}: sending login success (${up.textureProperties.size} properties)")
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
        selfEntityId = Limbo.ENTITY_ID; idMap.start(Limbo.ENTITY_ID); clientView.reset(); currentSpawn = null; batchOutstanding = 0; staleBatchAcks = 0
        server.tlog("[client] $playerName joined limbo"); join?.limboJoined()
        tryAttach()   // the backend's configuration overlaps with the client loading the limbo
        graceTask?.cancel(false)
        graceTask = channel.eventLoop().schedule({ graceOver = true; if (!clientLoaded) join?.limboLoaded(true); tryAttach(); switching?.let { releaseHeld(it) } }, LOAD_GRACE_SECONDS, TimeUnit.SECONDS)
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
        if (e.packetType == PacketType.Play.Client.PLAYER_LOADED && !clientLoaded) { clientLoaded = true; join?.limboLoaded(false); tryAttach(); channel.eventLoop().execute { switching?.let { releaseHeld(it) } } }
        if (e.packetType == PacketType.Play.Client.KEEP_ALIVE) {
            WrapperPlayClientKeepAlive(e); lastKeepAliveAck = System.nanoTime()
        }
        // everything else is ignored in limbo (movement, chat, settings...)
    }

    companion object { const val PROTOCOL = 777; var LOAD_GRACE_SECONDS = 5L }
}
