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

/** Master -> backend: one client packet frame (varint packet id + payload; no length prefix, not compressed, not encrypted). */
class PlayerPacketToBackend(val playerId: Long, val bytes: ByteArray) : NodeMessage

/** Backend -> master: one packet frame for the client, same framing as [PlayerPacketToBackend]. */
class PlayerPacketToClient(val playerId: Long, val bytes: ByteArray) : NodeMessage

/** Either direction: the player's session on the backend ends ([reason] is for logs; the backend has already sent any disconnect packet). */
data class PlayerDetach(val playerId: Long, val reason: String) : NodeMessage

/** Registers player relay messages (ids 48-63). Ids are wire format: never reuse one. */
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
    .register<PlayerPacketToBackend>(49, { m, b -> b.writeLong(m.playerId).writeByteArray(m.bytes) }, { b -> PlayerPacketToBackend(b.readLong(), b.readByteArray(MAX_PLAYER_FRAME)) })
    .register<PlayerPacketToClient>(50, { m, b -> b.writeLong(m.playerId).writeByteArray(m.bytes) }, { b -> PlayerPacketToClient(b.readLong(), b.readByteArray(MAX_PLAYER_FRAME)) })
    .register<PlayerDetach>(51, { m, b -> b.writeLong(m.playerId).writeString(m.reason, 256) }, { b -> PlayerDetach(b.readLong(), b.readString(256)) })

/** Largest relayed packet frame (vanilla limits clientbound packets to 8 MiB uncompressed; serverbound to 2 MiB). */
const val MAX_PLAYER_FRAME = 8 * 1024 * 1024
