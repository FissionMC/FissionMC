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

    // relay counters for MasterStats (client -> backend = "in")
    val framesIn = java.util.concurrent.atomic.AtomicLong(); val bytesIn = java.util.concurrent.atomic.AtomicLong()
    val framesOut = java.util.concurrent.atomic.AtomicLong(); val bytesOut = java.util.concurrent.atomic.AtomicLong()

    /** Relayed players per backend node id. */
    fun playersByNode(): Map<String, Int> = bindings.values.groupingBy { it.node.info.nodeId }.eachCount()

    /** Is a player of [nodeId] within [chunks] chunks (Chebyshev) of chunk ([cx],[cz]) in [dimension]? (positions come from the backends' PlayerPosition reports; a player whose position is not known yet counts as near) */
    fun playerNear(nodeId: String, dimension: String, cx: Int, cz: Int, chunks: Int): Boolean =
        bindings.values.any { b -> b.node.info.nodeId == nodeId && (!b.known || b.dim == dimension) && (!b.known || (Math.abs((b.x shr 4) - cx) <= chunks && Math.abs((b.z shr 4) - cz) <= chunks)) }

    /** `chat.local.format` + syntax, pushed to the backends (null until the master has read its settings). */
    @Volatile var chatFormat: net.fissionmc.protocol.ChatFormat? = null

    /** Static regions (null/off = every backend owns everything). */
    @Volatile var regions: net.fissionmc.master.region.RegionMap = net.fissionmc.master.region.RegionMap.OFF

    /** `travel.pet-radius` (blocks, 0 = pets stay): which tamed pets travel with a player that is flushed for a move. */
    @Volatile var petRadius: Int = 24

    /** `messages.local.enabled` (set by the client server): the backends announce joins and leaves themselves. */
    @Volatile var localAnnouncements: Boolean = false

    /** The permission level the master grants a player on every backend (4 for admins); set by the client server, which owns the admin set. */
    @Volatile var grantedLevel: (net.fissionmc.master.client.PlayerProfile) -> Int = { 0 }

    /** The canonical player data (null = no player data handling: tests, or disabled). */
    @Volatile var playerData: PlayerDataStore? = null

    // ---- traveling entities
    private class Pending(val origin: String, val transfer: EntityTransfer)
    private val pendingEntities = ConcurrentHashMap<java.util.UUID, Pending>()
    private val awaitingAck = ConcurrentHashMap<java.util.UUID, String>()   // player -> backend that still holds the originals

    /** A failed switch leaves the player where it is: its collected entities were never spawned elsewhere, the originals stay. */
    fun dropPendingEntities(uuid: java.util.UUID) { pendingEntities.remove(uuid); awaitingAck.remove(uuid) }

    fun setDataOwner(uuid: java.util.UUID, nodeId: String?) { playerData?.setOwner(uuid, nodeId) }

    /**
     * Asks the backend of binding [id] to save the player now and calls [done] when the data arrived (or after [timeoutMs]); without a store or a
     * live binding [done] runs at once. Used before the player moves to another backend.
     */
    fun flushPlayerData(id: Long, uuid: java.util.UUID, timeoutMs: Long, done: () -> Unit) {
        val store = playerData; val b = bindings[id]
        if (store == null || b == null || !b.node.link.isOpen) { done(); return }
        store.awaitSave(uuid, timeoutMs, done)
        b.node.link.send(PlayerDataFlush(id, petRadius))
    }

    /** Ids of the backends that are connected now. */
    fun liveNodeIds(): List<String> = registry.all.filter { it.link.isOpen }.map { it.info.nodeId }.sorted()

    /** Node id a binding talks to (null once detached). */
    fun nodeIdOf(id: Long): String? = bindings[id]?.node?.info?.nodeId

    /** Binding ids of all players currently attached (any backend), with their node id. */
    fun bindingNodes(): Map<Long, String> = bindings.mapValues { it.value.node.info.nodeId }

    /**
     * Attaches [client] to a live backend; returns the player id, or null if no backend is alive. With [nodeId] the named backend is used
     * (null when that one is not alive), otherwise round robin.
     */
    fun attach(client: RelayClient, nodeId: String? = null): Long? {
        val live = registry.all.filter { it.link.isOpen }.sortedBy { it.info.nodeId }
        if (live.isEmpty()) return null
        val node = if (nodeId != null) live.firstOrNull { it.info.nodeId == nodeId } ?: return null else live[Math.floorMod(rr.getAndIncrement(), live.size)]
        val id = ids.getAndIncrement()
        val p = client.profile
        bindings[id] = Binding(id, node, client)
        node.link.send(AnnouncementMode(localAnnouncements))
        chatFormat?.let { node.link.send(it) }
        if (regions.enabled) node.link.send(net.fissionmc.protocol.RegionMap(node.info.nodeId, regions.toRules()))   // the join-time send can arrive before the backend's handlers exist
        node.link.send(PlayerPermission(id, p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, grantedLevel(p)))   // before the attach: the join already sees the operator level
        playerData?.let { store ->   // the stored data goes first, the backend loads it when it creates the player
            val data = store.read(p.uuid)
            node.link.send(PlayerData(id, p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, data != null, data ?: ByteArray(0)))
            for (kind in intArrayOf(PlayerFileKind.STATS, PlayerFileKind.ADVANCEMENTS)) {
                val f = store.readFile(p.uuid, kind)
                node.link.send(PlayerFile(id, p.uuid.mostSignificantBits, p.uuid.leastSignificantBits, kind, f != null, f ?: ByteArray(0)))
            }
            pendingEntities.remove(p.uuid)?.let { pe ->   // pets, riders: they arrive before the player and are spawned once it is placed
                node.link.send(pe.transfer); awaitingAck[p.uuid] = pe.origin
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
    fun sendTo(id: Long, msg: NodeMessage) { bindings[id]?.node?.link?.takeIf { it.isOpen }?.send(msg) }

    /** Sends [msg] to every live backend except [nodeId]. */
    fun broadcastExcept(nodeId: String, msg: NodeMessage) { registry.broadcast(msg, nodeId) }

    /** Receives the backends' answers to global chat messages (set by the master main / tests). */
    @Volatile var chatResultHandler: ((GlobalChatResult) -> Unit)? = null

    fun isWritable(id: Long): Boolean = bindings[id]?.node?.link?.isWritable ?: true

    /** The client went away. */
    fun detach(id: Long, reason: String) {
        val b = bindings.remove(id) ?: return
        if (b.node.link.isOpen) b.node.link.send(PlayerDetach(id, reason))
    }

    fun handle(node: RegisteredNode, msg: NodeMessage): Boolean {
        when (msg) {
            is PlayerPacketToClient -> { framesOut.incrementAndGet(); bytesOut.addAndGet(msg.bytes.size.toLong()); bindings[msg.playerId]?.takeIf { it.node === node }?.let { it.client.deliverFrom(it.id, msg.bytes) } }
            is PlayerDetach -> bindings[msg.playerId]?.takeIf { it.node === node }?.let { bindings.remove(it.id); it.client.detachedFrom(it.id, msg.reason) }
            is GlobalChatResult -> chatResultHandler?.invoke(msg)
            is PlayerPermissionReport -> { val u = java.util.UUID(msg.uuidHigh, msg.uuidLow); bindings.values.firstOrNull { it.node === node && it.client.profile.uuid == u }?.client?.backendPermission(msg.level) }
            is PlayerPosition -> { val u = java.util.UUID(msg.uuidHigh, msg.uuidLow); bindings.values.firstOrNull { it.node === node && it.client.profile.uuid == u }?.let { it.dim = msg.dimension; it.x = msg.x; it.z = msg.z; it.known = true; it.client.playerMoved(msg.dimension, msg.x, msg.z) } }
            is EntityHandover -> registry[msg.toBackend]?.link?.takeIf { it.isOpen }?.send(msg)   // owner -> new owner, as is
            is EntityHandoverAck -> registry[msg.origin]?.link?.takeIf { it.isOpen }?.send(EntityRemove(msg.spawned))
            is PlayerFileSave -> playerData?.onFileSave(node.info.nodeId, java.util.UUID(msg.uuidHigh, msg.uuidLow), msg.kind, msg.bytes)
            is PlayerFlushed -> playerData?.flushed(java.util.UUID(msg.uuidHigh, msg.uuidLow))
            is EntityTransfer -> { val u = java.util.UUID(msg.playerHigh, msg.playerLow); if (msg.blobs.isEmpty()) pendingEntities.remove(u) else pendingEntities[u] = Pending(node.info.nodeId, msg) }
            is EntityTransferAck -> {
                val u = java.util.UUID(msg.playerHigh, msg.playerLow); val origin = awaitingAck.remove(u)
                if (origin != null && msg.spawned.isNotEmpty()) registry[origin]?.link?.takeIf { it.isOpen }?.send(EntityRemove(msg.spawned))
            }
            is PlayerDataSave -> playerData?.onSave(node.info.nodeId, java.util.UUID(msg.uuidHigh, msg.uuidLow), msg.nbt)
            else -> return false
        }
        return true
    }

    /** All players of a backend that left lose their session. */
    val listener = object : NodeRegistry.Listener {
        override fun left(node: RegisteredNode, reason: String) {
            bindings.values.filter { it.node === node }.forEach { bindings.remove(it.id); it.client.detachedFrom(it.id, "backend ${node.info.nodeId} lost: $reason", true) }
        }
    }
}
