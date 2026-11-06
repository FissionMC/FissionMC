package net.fissionmc.protocol

/** A property of the player's profile (skin textures etc.), forwarded unchanged from the master's authentication. */
data class ProfilePropertyData(val name: String, val value: String, val signature: String?)

/**
 * Master -> backend: a client authenticated on the master and is now yours. [playerId] is unique per master process.
 * The backend creates a virtual connection that starts in the configuration phase (no login; the profile comes from here).
 */
data class PlayerAttach(
    val playerId: Long, val uuidHigh: Long, val uuidLow: Long, val name: String,
    val properties: List<ProfilePropertyData>, val protocol: Int,
) : NodeMessage

/**
 * Master -> backend, sent right BEFORE the [PlayerAttach] of the same [playerId]: where the client really connects from.
 * [address] is the client IP literal, [port] its port, [hostname] the server address from the client's handshake.
 * Kept separate from [PlayerAttach] so id 48's layout stays unchanged. Without it the backend reports loopback.
 */
data class PlayerClientInfo(val playerId: Long, val address: String, val port: Int, val hostname: String) : NodeMessage

/** Master -> backend: one client packet frame (varint packet id + payload; no length prefix, not compressed, not encrypted). */
class PlayerPacketToBackend(val playerId: Long, val bytes: ByteArray) : NodeMessage

/** Backend -> master: one packet frame for the client, same framing as [PlayerPacketToBackend]. */
class PlayerPacketToClient(val playerId: Long, val bytes: ByteArray) : NodeMessage

/** Either direction: the player's session on the backend ends ([reason] is for logs; the backend has already sent any disconnect packet). */
data class PlayerDetach(val playerId: Long, val reason: String) : NodeMessage

/** One backend as the master sees it. */
data class NodeStat(val nodeId: String, val players: Int, val rttMicros: Long, val writable: Boolean)

/**
 * Master -> backend, every 2 s: what the master is doing (shown by `/fission status` and in spark reports as extra platform metadata).
 * Rates are per second over the last interval. Wire id 53.
 */
data class MasterStats(
    val timeMillis: Long, val clients: Int, val backends: Int, val relayed: Int,
    val framesInPerSec: Long, val framesOutPerSec: Long, val bytesInPerSec: Long, val bytesOutPerSec: Long,
    val heapUsedMb: Int, val heapMaxMb: Int, val cpuPercent: Int, val nodes: List<NodeStat>,
) : NodeMessage {
    /** Human readable lines, one fact per line. Pure, unit tested. */
    fun lines(): List<String> = buildList {
        add("master: $clients clients, $backends backends, $relayed relayed players")
        add("relay: in ${framesInPerSec} frames/s ${bytesInPerSec / 1024} KiB/s, out ${framesOutPerSec} frames/s ${bytesOutPerSec / 1024} KiB/s (client -> backend = in)")
        add("master jvm: heap $heapUsedMb / $heapMaxMb MB, cpu $cpuPercent%")
        nodes.forEach { add("link ${it.nodeId}: ${it.players} players, rtt ${if (it.rttMicros < 0) "n/a" else "%.2f ms".format(it.rttMicros / 1000.0)}${if (it.writable) "" else ", SATURATED"}") }
    }

    /** Flat key/value form for spark's extra platform metadata. */
    fun metadata(): Map<String, String> = buildMap {
        put("fission.master.clients", clients.toString()); put("fission.master.backends", backends.toString())
        put("fission.master.relayedPlayers", relayed.toString())
        put("fission.master.heapMb", "$heapUsedMb/$heapMaxMb"); put("fission.master.cpuPercent", cpuPercent.toString())
        put("fission.relay.framesInPerSec", framesInPerSec.toString()); put("fission.relay.framesOutPerSec", framesOutPerSec.toString())
        put("fission.relay.bytesInPerSec", bytesInPerSec.toString()); put("fission.relay.bytesOutPerSec", bytesOutPerSec.toString())
        nodes.forEach { put("fission.link.${it.nodeId}", "players=${it.players} rttMs=${if (it.rttMicros < 0) "n/a" else "%.2f".format(it.rttMicros / 1000.0)} writable=${it.writable}") }
    }
}

/**
 * Master -> backend (id 54): [playerId] (a relayed player of this backend) sent chat [text] while in GLOBAL chat mode. The master has
 * forwarded the client's chat packet right after it, unchanged (a dropped signed packet would break the signature chain). The backend
 * marks the next chat of that player with this text as global: it runs the Paper chat event path with an EMPTY recipient/viewer set (nothing
 * is delivered to players) and answers with [GlobalChatResult] carrying the same [seq].
 */
data class GlobalChat(val playerId: Long, val seq: Long, val text: String) : NodeMessage

/**
 * Backend -> master (id 55): what the chat events made of the message. [cancelled]: a plugin cancelled it (do not broadcast).
 * [modified]: plugins changed the message; then [messageJson] is the final Adventure component (JSON) and is trusted; otherwise the
 * master formats the original (sanitised) text itself. [displayName] is the sender's display name as plain text (for the `{displayname}` placeholder).
 */
data class GlobalChatResult(val playerId: Long, val seq: Long, val cancelled: Boolean, val modified: Boolean, val displayName: String, val messageJson: String) : NodeMessage

/**
 * Master -> every OTHER backend (id 56), after a global message was accepted: [text] is the final plain text, [messageJson] the final
 * component. The backend fires the chat events for a never-spawned ghost player named [name] with an empty viewer set: monitor only, a
 * plugin there cannot modify or cancel anything. [fromBackend] is the sender's backend node id.
 */
data class GlobalChatObserved(
    val uuidHigh: Long, val uuidLow: Long, val name: String, val fromBackend: String, val text: String, val messageJson: String,
) : NodeMessage

/**
 * Master -> backend (id 57), sent right BEFORE the [PlayerAttach] of the same [playerId]: the player's data as the master stores it
 * (vanilla player NBT, gzip compressed, exactly the bytes of a `<uuid>.dat` file). [present] false = the master has none (first join on the
 * network): the backend then uses its own local file if there is one, otherwise the player is new. The backend's `PlayerDataStorage.load` returns
 * this data instead of reading its own file.
 */
class PlayerData(val playerId: Long, val uuidHigh: Long, val uuidLow: Long, val present: Boolean, val nbt: ByteArray) : NodeMessage

/**
 * Backend -> master (id 58): the player's data after a save on the backend (autosave, disconnect, shutdown, a [PlayerDataFlush]); the bytes of the
 * `<uuid>.dat` the backend just wrote. The master keeps it only if the sending backend is the one it last attached the player to.
 */
class PlayerDataSave(val uuidHigh: Long, val uuidLow: Long, val nbt: ByteArray) : NodeMessage

/**
 * Master -> backend (id 59): the relayed player [playerId] is about to move to another backend: collect the entities that travel with it (pets within [petRadius] blocks,
 * 0 = none; riders), save it, and answer with [EntityTransfer], [PlayerDataSave], [PlayerFileSave]s and finally [PlayerFlushed].
 */
data class PlayerDataFlush(val playerId: Long, val petRadius: Int = 24) : NodeMessage

/**
 * Master -> backend (id 60), sent right before [PlayerAttach] and again whenever the master's admin set changes: the permission level the master
 * grants [playerId] on this backend. 4 = full operator ("admin == op level 4 on every backend"), 0 = nothing from the master (the backend's own ops
 * list applies). The backend answers the vanilla/Paper op checks with it and resends the player's command tree.
 */
data class PlayerPermission(val playerId: Long, val uuidHigh: Long, val uuidLow: Long, val level: Int) : NodeMessage

/**
 * Backend -> master (id 61): the permission level (0-4) the backend computed for a relayed player, sent whenever vanilla sends the player its
 * permission level (join, /op, /deop). The master treats level 4 on the player's CURRENT backend as admin for the master's own commands.
 */
data class PlayerPermissionReport(val uuidHigh: Long, val uuidLow: Long, val level: Int) : NodeMessage

/**
 * Master -> backend (id 70), sent before every [PlayerAttach]: whether this backend announces joins and leaves of relayed players itself (vanilla
 * messages, `messages.local.enabled=true` at the master). False (default): the master announces them network-wide and the backend stays silent
 * (the Join/Quit events still fire for plugins, their message is cleared).
 */
data class AnnouncementMode(val local: Boolean) : NodeMessage

/** Which per-player JSON file a [PlayerFile] / [PlayerFileSave] carries. */
object PlayerFileKind { const val STATS = 0; const val ADVANCEMENTS = 1 }

/**
 * Master -> backend (id 71), sent right after [PlayerData] and before [PlayerAttach]: the player's stats or advancements file as the master stores it
 * ([present] false: none stored, the backend keeps its own). The backend writes it into its local `players/stats|advancements` folder before the player joins.
 */
class PlayerFile(val playerId: Long, val uuidHigh: Long, val uuidLow: Long, val kind: Int, val present: Boolean, val bytes: ByteArray) : NodeMessage

/** Backend -> master (id 72): the stats/advancements file the backend just saved (same ownership rule as [PlayerDataSave]). */
class PlayerFileSave(val uuidHigh: Long, val uuidLow: Long, val kind: Int, val bytes: ByteArray) : NodeMessage

/** Backend -> master (id 73): the flush asked by [PlayerDataFlush] is complete (entities collected, player data, stats and advancements all sent). */
data class PlayerFlushed(val uuidHigh: Long, val uuidLow: Long) : NodeMessage

/** An entity that travels with a player: [kind] 0 = pet (owned by the player), 1 = rider on the player; [bytes] = zlib(NbtIo.write(entity NBT incl. its own passengers)). */
class EntityBlob(val kind: Int, val uuidHigh: Long, val uuidLow: Long, val bytes: ByteArray)
object EntityBlobKind { const val PET = 0; const val RIDER = 1 }

/**
 * Entities travelling with player ([playerHigh],[playerLow]) (id 74). Backend -> master on a flush (collected on the old backend), master -> backend before the
 * attach; the new backend spawns them after it placed the player and confirms with [EntityTransferAck].
 */
class EntityTransfer(val playerHigh: Long, val playerLow: Long, val blobs: List<EntityBlob>) : NodeMessage

/** New backend -> master (id 75): these entity uuids (pairs high/low) are now spawned here; the master tells the old backend to remove its copies. */
class EntityTransferAck(val playerHigh: Long, val playerLow: Long, val spawned: List<Long>) : NodeMessage

/** Master -> old backend (id 76): discard these entities (pairs high/low): they live on another backend now. */
class EntityRemove(val uuids: List<Long>) : NodeMessage

/** Largest player data blob accepted on the wire (a player file is a few KiB; ender chests and big inventories reach some hundred). */
const val MAX_PLAYER_DATA = 4 * 1024 * 1024
const val MAX_ENTITY_BLOB = 1024 * 1024
const val MAX_ENTITY_BLOBS = 64

/** Registers player relay messages (ids 48-63 and 70-76; 48-61 and 70-76 used). Ids are wire format: never reuse one. */
fun MessageRegistry.registerPlayerMessages(): MessageRegistry = this
    .register<PlayerAttach>(48, { m, b ->
        b.writeLong(m.playerId).writeLong(m.uuidHigh).writeLong(m.uuidLow).writeString(m.name, 64).writeVarInt(m.protocol)
        b.writeVarInt(m.properties.size)
        m.properties.forEach { p ->
            b.writeString(p.name, 64).writeString(p.value, 32767)
            b.writeBoolean(p.signature != null); p.signature?.let { b.writeString(it, 32767) }
        }
    }, { b ->
        val id = b.readLong(); val hi = b.readLong(); val lo = b.readLong(); val name = b.readString(64); val proto = b.readVarInt()
        val n = b.readVarInt(); require(n in 0..64) { "too many profile properties" }
        PlayerAttach(id, hi, lo, name, List(n) { ProfilePropertyData(b.readString(64), b.readString(32767), if (b.readBoolean()) b.readString(32767) else null) }, proto)
    })
    .register<PlayerClientInfo>(52, { m, b -> b.writeLong(m.playerId).writeString(m.address, 64).writeVarInt(m.port).writeString(m.hostname, 255) },
        { b -> PlayerClientInfo(b.readLong(), b.readString(64), b.readVarInt(), b.readString(255)) })
    .register<MasterStats>(53, { m, b ->
        b.writeLong(m.timeMillis).writeVarInt(m.clients).writeVarInt(m.backends).writeVarInt(m.relayed)
        b.writeLong(m.framesInPerSec).writeLong(m.framesOutPerSec).writeLong(m.bytesInPerSec).writeLong(m.bytesOutPerSec)
        b.writeVarInt(m.heapUsedMb).writeVarInt(m.heapMaxMb).writeVarInt(m.cpuPercent)
        b.writeVarInt(m.nodes.size); m.nodes.forEach { n -> b.writeString(n.nodeId, 64).writeVarInt(n.players).writeLong(n.rttMicros).writeBoolean(n.writable) }
    }, { b ->
        MasterStats(b.readLong(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readLong(), b.readLong(), b.readLong(), b.readLong(),
            b.readVarInt(), b.readVarInt(), b.readVarInt(), List(b.readVarInt().also { require(it in 0..1024) }) { NodeStat(b.readString(64), b.readVarInt(), b.readLong(), b.readBoolean()) })
    })
    .register<GlobalChat>(54, { m, b -> b.writeLong(m.playerId).writeLong(m.seq).writeString(m.text, 1024) }, { b -> GlobalChat(b.readLong(), b.readLong(), b.readString(1024)) })
    .register<GlobalChatResult>(55, { m, b -> b.writeLong(m.playerId).writeLong(m.seq).writeBoolean(m.cancelled).writeBoolean(m.modified).writeString(m.displayName, 256).writeString(m.messageJson, 262144) },
        { b -> GlobalChatResult(b.readLong(), b.readLong(), b.readBoolean(), b.readBoolean(), b.readString(256), b.readString(262144)) })
    .register<GlobalChatObserved>(56, { m, b -> b.writeLong(m.uuidHigh).writeLong(m.uuidLow).writeString(m.name, 64).writeString(m.fromBackend, 64).writeString(m.text, 1024).writeString(m.messageJson, 262144) },
        { b -> GlobalChatObserved(b.readLong(), b.readLong(), b.readString(64), b.readString(64), b.readString(1024), b.readString(262144)) })
    .register<PlayerData>(57, { m, b -> b.writeLong(m.playerId).writeLong(m.uuidHigh).writeLong(m.uuidLow).writeBoolean(m.present).writeByteArray(m.nbt, MAX_PLAYER_DATA) },
        { b -> PlayerData(b.readLong(), b.readLong(), b.readLong(), b.readBoolean(), b.readByteArray(MAX_PLAYER_DATA)) })
    .register<PlayerDataSave>(58, { m, b -> b.writeLong(m.uuidHigh).writeLong(m.uuidLow).writeByteArray(m.nbt, MAX_PLAYER_DATA) },
        { b -> PlayerDataSave(b.readLong(), b.readLong(), b.readByteArray(MAX_PLAYER_DATA)) })
    .register<PlayerDataFlush>(59, { m, b -> b.writeLong(m.playerId).writeVarInt(m.petRadius) }, { b -> PlayerDataFlush(b.readLong(), b.readVarInt()) })
    .register<PlayerPermission>(60, { m, b -> b.writeLong(m.playerId).writeLong(m.uuidHigh).writeLong(m.uuidLow).writeVarInt(m.level) }, { b -> PlayerPermission(b.readLong(), b.readLong(), b.readLong(), b.readVarInt()) })
    .register<PlayerPermissionReport>(61, { m, b -> b.writeLong(m.uuidHigh).writeLong(m.uuidLow).writeVarInt(m.level) }, { b -> PlayerPermissionReport(b.readLong(), b.readLong(), b.readVarInt()) })
    .register<AnnouncementMode>(70, { m, b -> b.writeBoolean(m.local) }, { b -> AnnouncementMode(b.readBoolean()) })
    .register<PlayerFile>(71, { m, b -> b.writeLong(m.playerId).writeLong(m.uuidHigh).writeLong(m.uuidLow).writeVarInt(m.kind).writeBoolean(m.present).writeByteArray(m.bytes, MAX_PLAYER_DATA) },
        { b -> PlayerFile(b.readLong(), b.readLong(), b.readLong(), b.readVarInt(), b.readBoolean(), b.readByteArray(MAX_PLAYER_DATA)) })
    .register<PlayerFileSave>(72, { m, b -> b.writeLong(m.uuidHigh).writeLong(m.uuidLow).writeVarInt(m.kind).writeByteArray(m.bytes, MAX_PLAYER_DATA) },
        { b -> PlayerFileSave(b.readLong(), b.readLong(), b.readVarInt(), b.readByteArray(MAX_PLAYER_DATA)) })
    .register<PlayerFlushed>(73, { m, b -> b.writeLong(m.uuidHigh).writeLong(m.uuidLow) }, { b -> PlayerFlushed(b.readLong(), b.readLong()) })
    .register<EntityTransfer>(74, { m, b -> b.writeLong(m.playerHigh).writeLong(m.playerLow).writeVarInt(m.blobs.size)
        m.blobs.forEach { e -> b.writeVarInt(e.kind).writeLong(e.uuidHigh).writeLong(e.uuidLow).writeByteArray(e.bytes, MAX_ENTITY_BLOB) } },
        { b -> val hi = b.readLong(); val lo = b.readLong(); val n = b.readVarInt(); require(n in 0..MAX_ENTITY_BLOBS) { "too many entities" }
            EntityTransfer(hi, lo, List(n) { EntityBlob(b.readVarInt(), b.readLong(), b.readLong(), b.readByteArray(MAX_ENTITY_BLOB)) }) })
    .register<EntityTransferAck>(75, { m, b -> b.writeLong(m.playerHigh).writeLong(m.playerLow).writeVarInt(m.spawned.size); m.spawned.forEach { b.writeLong(it) } },
        { b -> val hi = b.readLong(); val lo = b.readLong(); val n = b.readVarInt(); require(n in 0..MAX_ENTITY_BLOBS * 2) { "too many" }; EntityTransferAck(hi, lo, List(n) { b.readLong() }) })
    .register<EntityRemove>(76, { m, b -> b.writeVarInt(m.uuids.size); m.uuids.forEach { b.writeLong(it) } },
        { b -> val n = b.readVarInt(); require(n in 0..MAX_ENTITY_BLOBS * 2) { "too many" }; EntityRemove(List(n) { b.readLong() }) })
    .register<PlayerPacketToBackend>(49, { m, b -> b.writeLong(m.playerId).writeByteArray(m.bytes) }, { b -> PlayerPacketToBackend(b.readLong(), b.readByteArray(MAX_PLAYER_FRAME)) })
    .register<PlayerPacketToClient>(50, { m, b -> b.writeLong(m.playerId).writeByteArray(m.bytes) }, { b -> PlayerPacketToClient(b.readLong(), b.readByteArray(MAX_PLAYER_FRAME)) })
    .register<PlayerDetach>(51, { m, b -> b.writeLong(m.playerId).writeString(m.reason, 256) }, { b -> PlayerDetach(b.readLong(), b.readString(256)) })

/** Largest relayed packet frame (vanilla limits clientbound packets to 8 MiB uncompressed; serverbound to 2 MiB). */
const val MAX_PLAYER_FRAME = 8 * 1024 * 1024
