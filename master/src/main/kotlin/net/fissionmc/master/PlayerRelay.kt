package net.fissionmc.master

import net.fissionmc.net.NodeLink
import net.fissionmc.protocol.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the relay needs from a client connection (implemented by the client session; fakes in tests). */
interface RelayClient {
    val profile: net.fissionmc.master.client.PlayerProfile
    val protocol: Int
    /** The client's real address and the host name it connected with (forwarded to the backend), if known. */
    val remoteAddress: java.net.InetSocketAddress? get() = null
    val virtualHost: String get() = ""
    /** A packet frame (id + payload) from the backend for this client. Called on the node link's thread. */
    fun deliver(frame: ByteArray)
    /** The permission level (0-4) the player's current backend reports for it (join, /op, /deop). */
    fun backendPermission(level: Int) {}
    /** The sender's backend answered a permission check for a cross-backend command. */
    fun commandChecked(seq: Long, allowed: Boolean, present: Boolean = true) {}
    /** The player's backend reports where the player is now (block x/z in [dimension]); used for region border crossings. */
    fun playerMoved(dimension: String, x: Int, z: Int) {}
    /** Like [deliver], but says which binding (player id of an [PlayerRelay.attach]) the frame came from; a client with two bindings (backend switch) overrides this. */
    fun deliverFrom(bindingId: Long, frame: ByteArray) = deliver(frame)
    /** Like [detached], for a client that may hold two bindings at once. */
    fun detachedFrom(bindingId: Long, reason: String, backendLost: Boolean = false) = detached(reason, backendLost)
    /**
     * The backend session ended. [backendLost] is true when the backend itself went away (the client may be sent back to limbo);
     * otherwise the backend closed/kicked the player; [reason] starting with `kick:` carries a message for the client.
     */
    fun detached(reason: String, backendLost: Boolean = false)
}

/**
 * Master side of the player relay: picks a backend (round robin over live nodes) and moves packet frames between
 * client sessions and backends over the node link. Frames are opaque here; PacketEvents listeners (P3) hook in at the client session.
 */
class PlayerRelay(private val registry: NodeRegistry) {
    private class Binding(val id: Long, val node: RegisteredNode, val client: RelayClient) { @Volatile var dim: String = ""; @Volatile var x = 0; @Volatile var z = 0; @Volatile var known = false }
    private val ids = AtomicLong(1)
    private val bindings = ConcurrentHashMap<Long, Binding>()
    private val rr = java.util.concurrent.atomic.AtomicInteger()

    val attached: Int get() = bindings.size

    /** `/fission trace ...` across the master and all backends. */
    val trace = TraceHub(registry)

    // relay counters for MasterStats (client -> backend = "in")
    val framesIn = java.util.concurrent.atomic.AtomicLong(); val bytesIn = java.util.concurrent.atomic.AtomicLong()
    val framesOut = java.util.concurrent.atomic.AtomicLong(); val bytesOut = java.util.concurrent.atomic.AtomicLong()

    /** Relayed players per backend node id. */
    fun playersByNode(): Map<String, Int> = bindings.values.groupingBy { it.node.info.nodeId }.eachCount()

    /** Is a player of [nodeId] within [chunks] chunks (Chebyshev) of chunk ([cx],[cz]) in [dimension]? (positions come from the backends' PlayerPosition reports; a player whose position is not known yet counts as near) */
    fun playerNear(nodeId: String, dimension: String, cx: Int, cz: Int, chunks: Int): Boolean =
        bindings.values.any { b -> b.node.info.nodeId == nodeId && (!b.known || b.dim == dimension) && (!b.known || (Math.abs((b.x shr 4) - cx) <= chunks && Math.abs((b.z shr 4) - cz) <= chunks)) }

    /** The master-owned world settings (clocks, weather, game rules ...), set by the master main. */
    @Volatile var worldSettings: net.fissionmc.master.worldsettings.WorldSettingsHub? = null
    @Volatile var tickSync: net.fissionmc.master.ticksync.TickSyncHub? = null

    /** `chat.local.format` + syntax, pushed to the backends (null until the master has read its settings). */
    @Volatile var chatFormat: net.fissionmc.protocol.ChatFormat? = null

    /** Static regions (null/off = every backend owns everything). */
    @Volatile var regions: net.fissionmc.master.region.RegionMap = net.fissionmc.master.region.RegionMap.OFF

    /** `travel.pet-radius` (blocks, 0 = pets stay): which tamed pets travel with a player that is flushed for a move. */
    @Volatile var petRadius: Int = 24

    /** `messages.local.enabled` (set by the client server): the backends announce joins and leaves themselves. */
    @Volatile var localAnnouncements: Boolean = false

    /** The operator level the master grants a player on every backend (highest level of its groups, 4 = admin); set by the client server, which owns the permissions. */
    @Volatile var grantedLevel: (net.fissionmc.master.client.PlayerProfile) -> Int = { 0 }
    /** The group nodes the master grants a player on every backend (real Bukkit permissions there). */
    @Volatile var grantedNodes: (net.fissionmc.master.client.PlayerProfile) -> List<String> = { emptyList() }

    /** The canonical player data (null = no player data handling: tests, or disabled). */
    @Volatile var playerData: PlayerDataStore? = null

    // ---- traveling entities
    private class Pending(val origin: String, val transfer: EntityTransfer, val at: Long = System.currentTimeMillis())
    private val pendingTtlMs = 60_000L   // a collected pet transfer older than this belongs to a switch that never happened
    private val pendingEntities = ConcurrentHashMap<java.util.UUID, Pending>()
    private class Awaiting(val origin: String, val at: Long)
    private val awaitingAck = ConcurrentHashMap<java.util.UUID, Awaiting>()   // player -> backend that still holds the originals (forgotten after [ackTtlMs]: a player who left before the ack must not make a later ack remove entities)
    @Volatile internal var ackTtlMs = 120_000L

    /** A failed switch leaves the player where it is: its collected entities were never spawned elsewhere, the originals stay. */
    /** The player left before the transfer was delivered: the originals stay where they are (nothing was spawned elsewhere). */
    fun dropPendingTransfer(uuid: java.util.UUID) { pendingEntities.remove(uuid) }
    private fun purgeStalePending() { val now = System.currentTimeMillis(); pendingEntities.values.removeIf { now - it.at > pendingTtlMs } }

    fun dropPendingEntities(uuid: java.util.UUID) { pendingEntities.remove(uuid); awaitingAck.remove(uuid) }

    fun setDataOwner(uuid: java.util.UUID, nodeId: String?) { playerData?.setOwner(uuid, nodeId) }

    /**
     * Asks the backend of binding [id] to save the player now and calls [done] when the data arrived (or after [timeoutMs]); without a store or a
     * live binding [done] runs at once. Used before the player moves to another backend.
     */
    fun flushPlayerData(id: Long, uuid: java.util.UUID, timeoutMs: Long, onTimeout: (() -> Unit)? = null, done: () -> Unit) {
        val store = playerData; val b = bindings[id]
        if (store == null || b == null || !b.node.link.isOpen) { done(); return }
        store.awaitSave(uuid, b.node.scaled(timeoutMs), onTimeout, done)   // the backend saves on its game thread: a slow ticking one gets more time
        b.node.link.send(PlayerDataFlush(id, petRadius))
    }

    /** Ids of the backends that are connected now. */
    fun liveNodeIds(): List<String> = registry.all.filter { it.link.isOpen }.map { it.info.nodeId }.sorted()

    /** Ids of the backends that are connected and ready to log players in (a booting backend has joined the link but its server loop does not run yet). */
    fun readyNodeIds(): List<String> = registry.all.filter { it.link.isOpen && it.ready }.map { it.info.nodeId }.sorted()
    /** Connected but not ready (still booting). */
    fun bootingNodeIds(): List<String> = registry.all.filter { it.link.isOpen && !it.ready }.map { it.info.nodeId }.sorted()
    fun isReady(nodeId: String): Boolean = registry[nodeId]?.let { it.link.isOpen && it.ready } == true
    fun isBooting(nodeId: String): Boolean = registry[nodeId]?.let { it.link.isOpen && !it.ready } == true

    /** Node id a binding talks to (null once detached). */
    fun nodeIdOf(id: Long): String? = bindings[id]?.node?.info?.nodeId

    /** Binding ids of all players currently attached (any backend), with their node id. */
    fun bindingNodes(): Map<Long, String> = bindings.mapValues { it.value.node.info.nodeId }

    /**
     * Attaches [client] to a live backend that is ready; returns the player id, or null if none is. With [nodeId] the named backend is used
     * (null when that one is not alive or still booting), otherwise round robin over the ready ones.
     */
    fun attach(client: RelayClient, nodeId: String? = null): Long? {
        val live = registry.all.filter { it.link.isOpen && it.ready }.sortedBy { it.info.nodeId }
        if (live.isEmpty()) return null
        val node = if (nodeId != null) live.firstOrNull { it.info.nodeId == nodeId } ?: return null else live[Math.floorMod(rr.getAndIncrement(), live.size)]
        val id = ids.getAndIncrement()
        val p = client.profile
        bindings[id] = Binding(id, node, client)
        node.link.send(AnnouncementMode(localAnnouncements))
        chatFormat?.let { node.link.send(it) }
        if (regions.enabled) node.link.send(net.fissionmc.protocol.RegionMap(node.info.nodeId, regions.toRules(), regions.mobAiBorder))   // the join-time send can arrive before the backend's handlers exist
        node.link.send(PlayerPermission(id, p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, grantedLevel(p), grantedNodes(p)))   // before the attach: the join already sees the operator level
        playerData?.let { store ->   // the stored data goes first, the backend loads it when it creates the player
            val data = store.read(p.uuid)
            node.link.send(PlayerData(id, p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, data != null, data ?: ByteArray(0)))
            for (kind in intArrayOf(PlayerFileKind.STATS, PlayerFileKind.ADVANCEMENTS)) {
                val f = store.readFile(p.uuid, kind)
                node.link.send(PlayerFile(id, p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, kind, f != null, f ?: ByteArray(0)))
            }
            pendingEntities.remove(p.uuid)?.takeIf { System.currentTimeMillis() - it.at <= pendingTtlMs }?.let { pe ->   // pets, riders: they arrive before the player and are spawned once it is placed
                node.link.send(pe.transfer); awaitingAck[p.uuid] = Awaiting(pe.origin, System.currentTimeMillis()); if (awaitingAck.size > 4096) { val cut = System.currentTimeMillis() - ackTtlMs; awaitingAck.values.removeIf { it.at < cut } }
            }
            store.setOwner(p.uuid, node.info.nodeId)
        }
        client.remoteAddress?.let { a -> node.link.send(PlayerClientInfo(id, a.address?.hostAddress ?: a.hostString, a.port, client.virtualHost)) }
        node.link.send(PlayerAttach(id, p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, p.name,
            p.properties.map { ProfilePropertyData(it.name, it.value, it.signature) }, client.protocol))
        return id
    }

    /** Client frame towards the backend. Returns false when the link is saturated (caller should stop reading the client). */
    fun toBackend(id: Long, frame: ByteArray): Boolean {
        val b = bindings[id] ?: return true
        b.node.link.send(PlayerPacketToBackend(id, frame)); framesIn.incrementAndGet(); bytesIn.addAndGet(frame.size.toLong())
        return b.node.link.isWritable
    }

    /** Sends a control message (not a packet frame) to the backend a binding talks to. */
    fun sendToNode(nodeId: String, msg: NodeMessage) { registry[nodeId]?.link?.takeIf { it.isOpen }?.send(msg) }
    fun sendTo(id: Long, msg: NodeMessage) { bindings[id]?.node?.link?.takeIf { it.isOpen }?.send(msg) }

    /** Sends [msg] to every live backend except [nodeId]. */
    fun broadcastExcept(nodeId: String, msg: NodeMessage) { registry.broadcast(msg, nodeId) }

    /** Ghost player reports of the backends (GhostPose/GhostMeta/GhostSwing); set by the client server. */
    @Volatile var ghostHandler: ((NodeMessage) -> Unit)? = null

    /** Receives the backends' answers to global chat messages (set by the master main / tests). */
    @Volatile var chatResultHandler: ((GlobalChatResult) -> Unit)? = null

    fun isWritable(id: Long): Boolean = bindings[id]?.node?.link?.isWritable ?: true

    /** The client went away. */
    fun detach(id: Long, reason: String) {
        val b = bindings.remove(id) ?: return
        if (b.node.link.isOpen) b.node.link.send(PlayerDetach(id, reason))
    }

    /** A message that names its sender (`origin`) must name the node it came from: a backend cannot speak for another one. */
    private fun fromSelf(node: RegisteredNode, origin: String, what: String): Boolean {
        if (origin == node.info.nodeId) return true
        net.fissionmc.net.pairing.Audit.failure("origin:${node.info.nodeId}", "node ${node.info.nodeId} sent $what with origin '${net.fissionmc.net.pairing.Audit.clean(origin, 48)}' (spoofed sender): dropped"); return false
    }
    private fun boundHere(node: RegisteredNode, high: Long, low: Long): Boolean { val u = java.util.UUID(high, low); return bindings.values.any { it.node === node && it.client.profile.uuid == u } }
    private fun boundAt(nodeId: String, high: Long, low: Long): Boolean { val u = java.util.UUID(high, low); return bindings.values.any { it.node.info.nodeId == nodeId && it.client.profile.uuid == u } }
    /** (origin, receiver) pairs of handovers the master forwarded in the last two minutes: an ack that names an origin must answer one of them. */
    private class Offered(var at: Long, val ids: HashMap<Pair<Long, Long>, Long> = HashMap())   // uuid -> when it was offered
    private val recentHandovers = ConcurrentHashMap<String, Offered>()
    /** What was offered: the blobs' uuids and, read from their NBT, every passenger below them. An ack may only name those (a passenger's uuid cannot be known any other way). */
    private fun note(origin: String, to: String, blobs: List<EntityBlob> = emptyList()) {
        val now = System.nanoTime()
        val o = recentHandovers.compute(origin + "\u0000" + to) { _, old -> (old ?: Offered(now)).also { it.at = now } }!!
        synchronized(o) {
            if (o.ids.size > 1024) o.ids.values.removeIf { now - it > 120_000_000_000L }   // an entity that was offered long ago is not acknowledged any more
            for (b in blobs) {
                if (o.ids.size >= MAX_OFFERED) break
                o.ids[b.uuidHigh to b.uuidLow] = now
                try { for (u in NbtUuids.entityTree(b.bytes)) o.ids[u] = now } catch (t: Throwable) { passengerParseFailures.incrementAndGet() }   // an unreadable blob offers only its own uuid
            }
        }
        if (recentHandovers.size > 4096) { val cut = now - 120_000_000_000L; recentHandovers.values.removeIf { it.at < cut } }
    }
    private fun handoverSeen(origin: String, receiver: String): Boolean = recentHandovers[origin + "\u0000" + receiver]?.let { System.nanoTime() - it.at < 120_000_000_000L } == true
    /** The part of [spawned] (uuid pairs) that was offered by [origin] to [receiver]. */
    private fun offeredOnly(origin: String, receiver: String, spawned: List<Long>): List<Long> {
        val o = recentHandovers[origin + "\u0000" + receiver] ?: return emptyList()
        val out = ArrayList<Long>(spawned.size)
        synchronized(o) { var i = 0; while (i + 1 < spawned.size) { if (o.ids.containsKey(spawned[i] to spawned[i + 1])) { out += spawned[i]; out += spawned[i + 1] }; i += 2 } }
        if (out.size < spawned.size) { unofferedAckEntries.addAndGet(((spawned.size - out.size) / 2).toLong()); net.fissionmc.net.pairing.Audit.failure("ack:$receiver", "$receiver acknowledged ${(spawned.size - out.size) / 2} entities that $origin never offered it: ignored") }
        return out
    }
    /** PlayerFlushed from a node that does not hold the player / with nobody waiting for it (stale or foreign). */
    @JvmField val flushFromOtherNode = AtomicLong()
    @JvmField val flushNoWaiter = AtomicLong()
    @JvmField val passengerParseFailures = AtomicLong()
    @JvmField val unofferedAckEntries = AtomicLong()
    private val MAX_OFFERED = 100_000
    /** (owner node, player, player's node) of the pickups the master asked for in the last 30 s: an [ItemGive] must answer one of them (any backend could otherwise hand any item to any online player). */
    private val recentPickups = ConcurrentHashMap<String, Long>()
    private fun pickupKey(owner: String, player: java.util.UUID, playerNode: String) = "$owner\u0000$player\u0000$playerNode"
    fun notePickup(owner: String, player: java.util.UUID, playerNode: String) {
        val now = System.nanoTime(); recentPickups[pickupKey(owner, player, playerNode)] = now
        if (recentPickups.size > 4096) recentPickups.values.removeIf { now - it > 30_000_000_000L }
    }
    private fun pickupAsked(owner: String, player: java.util.UUID, playerNode: String) = recentPickups[pickupKey(owner, player, playerNode)]?.let { System.nanoTime() - it < 30_000_000_000L } == true
    /** (owner, item entity) -> (the node the give went to, when) of the tracked gives forwarded in the last 60 s: an [ItemGiveAck] must come from that node. */
    private val recentGives = ConcurrentHashMap<String, Pair<String, Long>>()
    private fun giveKey(owner: String, h: Long, l: Long) = "$owner\u0000$h\u0000$l"
    private val maxPendingEntities = 1024

    /** [baseMs], stretched for a backend that ticks slowly, for the backend of binding [id] (the binding's own backend, 50 ms/tick when unknown). */
    fun scaledFor(id: Long, baseMs: Long): Long = bindings[id]?.node?.scaled(baseMs) ?: baseMs
    fun scaledForNode(nodeId: String, baseMs: Long): Long = registry[nodeId]?.scaled(baseMs) ?: baseMs

    fun handle(node: RegisteredNode, msg: NodeMessage): Boolean {
        when (msg) {
            is NodeReady -> { net.fissionmc.net.trace.Trace.emit("node.ready") { str("node", node.info.nodeId); long("bootMs", msg.bootMs) }; registry.markReady(node) }
            is net.fissionmc.protocol.NodeTickRate -> { if (node.tickMs != msg.msPerTick) net.fissionmc.net.trace.Trace.emit("node.tickrate") { str("node", node.info.nodeId); int("ms", msg.msPerTick) }; node.tickMs = msg.msPerTick }
            is PlayerPacketToClient -> { framesOut.incrementAndGet(); bytesOut.addAndGet(msg.bytes.size.toLong()); bindings[msg.playerId]?.takeIf { it.node === node }?.let { it.client.deliverFrom(it.id, msg.bytes) } }
            is PlayerDetach -> bindings[msg.playerId]?.takeIf { it.node === node }?.let { bindings.remove(it.id); it.client.detachedFrom(it.id, msg.reason) }
            is GlobalChatResult -> if (bindings[msg.playerId]?.node === node) chatResultHandler?.invoke(msg)   // only the backend that hosts the player answers for it
            is TraceDump -> trace.onReply(node, msg)
            is GhostPose -> if (boundHere(node, msg.uuidHigh, msg.uuidLow)) ghostHandler?.invoke(msg)   // a backend reports ghosts of the players it hosts, not of others
            is GhostMeta -> if (boundHere(node, msg.uuidHigh, msg.uuidLow)) ghostHandler?.invoke(msg)
            is GhostSwing -> if (boundHere(node, msg.uuidHigh, msg.uuidLow)) ghostHandler?.invoke(msg)
            is EntityGhostBatch -> if (fromSelf(node, msg.origin, "EntityGhostBatch")) ghostHandler?.invoke(msg)
            is EntityGhostLinks -> if (fromSelf(node, msg.origin, "EntityGhostLinks")) ghostHandler?.invoke(msg)
            is CommandCheckResult -> bindings[msg.playerId]?.takeIf { it.node === node }?.client?.commandChecked(msg.seq, msg.allowed, msg.present)
            is PlayerPermissionReport -> { val u = java.util.UUID(msg.uuidHigh, msg.uuidLow); bindings.values.firstOrNull { it.node === node && it.client.profile.uuid == u }?.client?.backendPermission(msg.level) }
            is PlayerPosition -> { net.fissionmc.net.trace.Trace.emit("player.position") { str("from", node.info.nodeId); player(null, msg.uuidHigh, msg.uuidLow); str("dim", msg.dimension); int("x", msg.x); int("z", msg.z) }; val u = java.util.UUID(msg.uuidHigh, msg.uuidLow); bindings.values.firstOrNull { it.node === node && it.client.profile.uuid == u }?.let { it.dim = msg.dimension; it.x = msg.x; it.z = msg.z; it.known = true; it.client.playerMoved(msg.dimension, msg.x, msg.z) } }
            is EntityHandover -> if (fromSelf(node, msg.origin, "EntityHandover")) { note(msg.origin, msg.toBackend, msg.blobs); traceHandover(node, msg).let { registry[msg.toBackend] }?.link?.takeIf { it.isOpen }?.send(msg) }   // owner -> new owner, as is
            is BorderForward -> if (fromSelf(node, msg.origin, "BorderForward")) { net.fissionmc.net.trace.Trace.emit("border.forward") { str("from", msg.origin); str("to", msg.toBackend); str("dim", msg.dimension); int("ticks", msg.ticks.size); int("neighbors", msg.neighbors.size); bool("delivered", registry[msg.toBackend]?.link?.isOpen == true) }; registry[msg.toBackend]?.link?.takeIf { it.isOpen }?.send(msg) }   // ghost-edge ticks and neighbour updates: to the owner of the chunk, as is
            is ItemTransfer -> if (fromSelf(node, msg.origin, "ItemTransfer")) { net.fissionmc.net.trace.Trace.emit("item.transfer") { str("from", msg.origin); str("to", msg.toBackend); str("dim", msg.dimension); int("x", msg.x); int("z", msg.z); int("stacks", msg.stacks.size) }; registry[msg.toBackend]?.link?.takeIf { it.isOpen }?.send(msg) }
            is BlockEntitySignal -> if (fromSelf(node, msg.origin, "BlockEntitySignal")) registry[msg.toBackend]?.link?.takeIf { it.isOpen }?.send(msg)
            is ItemGive -> if (boundAt(msg.toBackend, msg.playerHigh, msg.playerLow) && pickupAsked(node.info.nodeId, java.util.UUID(msg.playerHigh, msg.playerLow), msg.toBackend)) registry[msg.toBackend]?.link?.takeIf { it.isOpen }?.send(msg)
                else net.fissionmc.net.pairing.Audit.failure("give:${node.info.nodeId}", "dropped an item give from ${node.info.nodeId} for ${java.util.UUID(msg.playerHigh, msg.playerLow)} on ${msg.toBackend}: the player is not there or no pickup was asked (the item is lost)")
            is ItemGiveTracked -> if (fromSelf(node, msg.origin, "ItemGiveTracked")) {
                val now = System.nanoTime()
                val target = registry[msg.toBackend]?.link?.takeIf { it.isOpen }
                if (target != null && boundAt(msg.toBackend, msg.playerHigh, msg.playerLow) && pickupAsked(node.info.nodeId, java.util.UUID(msg.playerHigh, msg.playerLow), msg.toBackend)) {
                    if (recentGives.size > 4096) recentGives.values.removeIf { now - it.second > 60_000_000_000L }
                    recentGives[giveKey(node.info.nodeId, msg.entityHigh, msg.entityLow)] = msg.toBackend to now
                    target.send(msg)
                } else {
                    // refused: tell the owner at once so it puts the item back instead of waiting for its timeout
                    net.fissionmc.net.pairing.Audit.failure("give:${node.info.nodeId}", "refused an item give from ${node.info.nodeId} for ${java.util.UUID(msg.playerHigh, msg.playerLow)} on ${msg.toBackend}: the player is not there, no pickup was asked or the node is down (the owner puts the item back)")
                    node.link.takeIf { it.isOpen }?.send(ItemGiveAck(node.info.nodeId, msg.entityHigh, msg.entityLow, false))
                }
            }
            is ItemGiveAck -> {
                val k = giveKey(msg.toBackend, msg.entityHigh, msg.entityLow)
                val e = recentGives[k]
                if (e != null && e.first == node.info.nodeId && System.nanoTime() - e.second < 60_000_000_000L && recentGives.remove(k, e)) registry[msg.toBackend]?.link?.takeIf { it.isOpen }?.send(msg)   // only the node the give went to answers it, once
            }
            is GhostDemand -> if (fromSelf(node, msg.origin, "GhostDemand")) traceGhost("ghost.demand", msg.origin, msg.toBackend, msg.dimension, msg.chunks.size).let { registry[msg.toBackend]?.link?.takeIf { it.isOpen }?.send(msg) }   // viewer -> owner: keep these chunks ticking
            is GhostSync -> if (fromSelf(node, msg.origin, "GhostSync")) { net.fissionmc.net.trace.Trace.emit("ghost.sync") { str("from", msg.origin); str("to", msg.toBackend); str("dim", msg.dimension); int("cx", msg.cx); int("cz", msg.cz); int("sections", msg.hashes.size) }; registry[msg.toBackend]?.link?.takeIf { it.isOpen }?.send(msg) }     // viewer -> owner: hashes of the ghost copy
            is GhostFix -> if (fromSelf(node, msg.origin, "GhostFix")) { net.fissionmc.net.trace.Trace.emit("ghost.fix") { str("from", msg.origin); str("to", msg.toBackend); str("dim", msg.dimension); int("cx", msg.cx); int("cz", msg.cz); ints("sections", msg.sections.map { it.index }.toIntArray()) }; registry[msg.toBackend]?.link?.takeIf { it.isOpen }?.send(msg) }      // owner -> viewer: the sections that differ
            is EntityHandoverAck -> if (handoverSeen(msg.origin, node.info.nodeId)) { net.fissionmc.net.trace.Trace.emit("handover.ack") { str("origin", msg.origin); str("from", node.info.nodeId); int("spawned", msg.spawned.size) }; offeredOnly(msg.origin, node.info.nodeId, msg.spawned).let { ok -> if (ok.isNotEmpty()) registry[msg.origin]?.link?.takeIf { it.isOpen }?.send(EntityRemove(ok)) } }
            is PlayerFileSave -> playerData?.onFileSave(node.info.nodeId, java.util.UUID(msg.uuidHigh, msg.uuidLow), msg.kind, msg.bytes)
            is PlayerFlushed -> { val u = java.util.UUID(msg.uuidHigh, msg.uuidLow)
                // (not bound to a request: an answer to a flush that already timed out can complete the next one early. Counted and logged here; a sequence number would need new wire messages)
                val owner = playerData?.owner(u)
                if (owner != null && owner != node.info.nodeId) { flushFromOtherNode.incrementAndGet(); net.fissionmc.net.pairing.Audit.failure("flushed:${node.info.nodeId}", "${node.info.nodeId} confirmed a flush of $u, but the player belongs to $owner") }
                if (playerData?.flushed(u) == false) { flushNoWaiter.incrementAndGet(); if (net.fissionmc.net.FissionDebug.on) net.fissionmc.net.pairing.Audit.failure("flushed-late:${node.info.nodeId}", "late PlayerFlushed for $u from ${node.info.nodeId}: no flush was waiting (it timed out already)") } }
            is EntityTransfer -> { purgeStalePending(); if (boundHere(node, msg.playerHigh, msg.playerLow) && (pendingEntities.size < maxPendingEntities || pendingEntities.containsKey(java.util.UUID(msg.playerHigh, msg.playerLow)))) { net.fissionmc.net.trace.Trace.emit("travel.transfer") { str("from", node.info.nodeId); player(null, msg.playerHigh, msg.playerLow); int("entities", msg.blobs.size) }; val u = java.util.UUID(msg.playerHigh, msg.playerLow); if (msg.blobs.isEmpty()) pendingEntities.remove(u) else pendingEntities[u] = Pending(node.info.nodeId, msg) } }
            is EntityTransferAck -> if (boundHere(node, msg.playerHigh, msg.playerLow)) { net.fissionmc.net.trace.Trace.emit("travel.transfer.ack") { str("from", node.info.nodeId); player(null, msg.playerHigh, msg.playerLow); int("spawned", msg.spawned.size) }
                val u = java.util.UUID(msg.playerHigh, msg.playerLow); val origin = awaitingAck.remove(u)?.takeIf { System.currentTimeMillis() - it.at <= ackTtlMs }?.origin
                if (origin != null && msg.spawned.isNotEmpty()) registry[origin]?.link?.takeIf { it.isOpen }?.send(EntityRemove(msg.spawned))
            }
            is net.fissionmc.protocol.WorldSettingsHello, is net.fissionmc.protocol.WorldSettingChange -> worldSettings?.onMessage(node, msg)
            is net.fissionmc.protocol.TickSyncHello, is net.fissionmc.protocol.TickSyncChange -> tickSync?.onMessage(node, msg)
            is PlayerDataSave -> playerData?.onSave(node.info.nodeId, java.util.UUID(msg.uuidHigh, msg.uuidLow), msg.nbt)
            else -> return false
        }
        return true
    }

    private fun traceHandover(node: RegisteredNode, m: EntityHandover) = net.fissionmc.net.trace.Trace.emit("handover") { str("from", node.info.nodeId); str("origin", m.origin); str("to", m.toBackend); str("dim", m.dimension); int("entities", m.blobs.size); ints("kinds", m.blobs.map { it.kind }.toIntArray()); strs("uuids", m.blobs.map { java.util.UUID(it.uuidHigh, it.uuidLow).toString() }) }
    private fun traceGhost(type: String, from: String, to: String, dim: String, n: Int) = net.fissionmc.net.trace.Trace.emit(type) { str("from", from); str("to", to); str("dim", dim); int("chunks", n) }

    /** All players of a backend that left lose their session. */
    val listener = object : NodeRegistry.Listener {
        override fun left(node: RegisteredNode, reason: String) {
            bindings.values.filter { it.node === node }.forEach { bindings.remove(it.id); it.client.detachedFrom(it.id, "backend ${node.info.nodeId} lost: $reason", true) }
        }
    }
}
