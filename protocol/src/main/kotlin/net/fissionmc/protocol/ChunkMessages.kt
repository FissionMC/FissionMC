package net.fissionmc.protocol

/** Which of the three vanilla region storages a chunk blob belongs to. */
object ChunkKind {
    const val CHUNK: Int = 0
    const val ENTITIES: Int = 1
    const val POI: Int = 2
}

/** Largest chunk blob on the wire (zlib compressed vanilla NBT; real chunks are some KiB to a few hundred KiB). */
const val MAX_CHUNK_BLOB = 4 * 1024 * 1024

/**
 * Chunk authority messages, wire ids 64-69. [dimension] is the level id (`minecraft:overworld`), [kind] a [ChunkKind],
 * blobs are `zlib(NbtIo.write(tag))`, the payload of a region file slot.
 */

/**
 * Backend -> master (64): who has this chunk? Answered by [ChunkData] or [ChunkAbsent]. [peek] true = only look (structure scans of unloaded neighbours):
 * never a generation lease, never parked, absent means [ChunkAbsent] with lease false at once.
 */
data class ChunkRequest(val reqId: Long, val dimension: String, val kind: Int, val x: Int, val z: Int, val peek: Boolean = false) : NodeMessage

/** Master -> backend (65): the stored chunk. [status] is the rank of the chunk status (0 for entities/poi), [version] the master's version of it. */
class ChunkData(val reqId: Long, val status: Int, val version: Long, val bytes: ByteArray) : NodeMessage

/** Master -> backend (66): the master has no such chunk. [lease] true: generate it yourself (and push it); false: nothing is expected. */
data class ChunkAbsent(val reqId: Long, val lease: Boolean) : NodeMessage

/** Backend -> master (67): a chunk generated or saved on the backend. [baseVersion] is the version the backend loaded or last pushed (0 = generated here). */
class ChunkPush(val dimension: String, val kind: Int, val x: Int, val z: Int, val status: Int, val baseVersion: Long, val bytes: ByteArray) : NodeMessage

/** Master -> backend (68): the answer to a [ChunkPush]. */
data class ChunkStored(val dimension: String, val kind: Int, val x: Int, val z: Int, val accepted: Boolean, val version: Long) : NodeMessage

/** Master -> the other backends (69): a new version of a chunk exists. */
data class ChunkAvailable(val dimension: String, val kind: Int, val x: Int, val z: Int, val version: Long) : NodeMessage

/** Registers the chunk authority messages (ids 64-69). Ids are wire format: never reuse one. */
fun MessageRegistry.registerChunkMessages(): MessageRegistry = this
    .register<ChunkRequest>(64, { m, b -> b.writeLong(m.reqId).writeString(m.dimension, 128).writeByte(m.kind).writeInt(m.x).writeInt(m.z).writeBoolean(m.peek) },
        { b -> ChunkRequest(b.readLong(), b.readString(128), b.readByte().toInt(), b.readInt(), b.readInt(), b.readBoolean()) })
    .register<ChunkData>(65, { m, b -> b.writeLong(m.reqId).writeVarInt(m.status).writeLong(m.version).writeByteArray(m.bytes, MAX_CHUNK_BLOB) },
        { b -> ChunkData(b.readLong(), b.readVarInt(), b.readLong(), b.readByteArray(MAX_CHUNK_BLOB)) })
    .register<ChunkAbsent>(66, { m, b -> b.writeLong(m.reqId).writeBoolean(m.lease) }, { b -> ChunkAbsent(b.readLong(), b.readBoolean()) })
    .register<ChunkPush>(67, { m, b -> b.writeString(m.dimension, 128).writeByte(m.kind).writeInt(m.x).writeInt(m.z).writeVarInt(m.status).writeLong(m.baseVersion).writeByteArray(m.bytes, MAX_CHUNK_BLOB) },
        { b -> ChunkPush(b.readString(128), b.readByte().toInt(), b.readInt(), b.readInt(), b.readVarInt(), b.readLong(), b.readByteArray(MAX_CHUNK_BLOB)) })
    .register<ChunkStored>(68, { m, b -> b.writeString(m.dimension, 128).writeByte(m.kind).writeInt(m.x).writeInt(m.z).writeBoolean(m.accepted).writeLong(m.version) },
        { b -> ChunkStored(b.readString(128), b.readByte().toInt(), b.readInt(), b.readInt(), b.readBoolean(), b.readLong()) })
    .register<ChunkAvailable>(69, { m, b -> b.writeString(m.dimension, 128).writeByte(m.kind).writeInt(m.x).writeInt(m.z).writeLong(m.version) },
        { b -> ChunkAvailable(b.readString(128), b.readByte().toInt(), b.readInt(), b.readInt(), b.readLong()) })

/** Vanilla 26.3 chunk status order (`ChunkStatus.getIndex()`); the rank is what the store compares. Unknown names rank 0. */
object ChunkStatusRank {
    private val order = listOf("empty", "structure_starts", "structure_references", "biomes", "terrain", "features", "initialize_light", "light", "spawn", "full")
    const val FULL = 9
    fun of(status: String?): Int = order.indexOf(status?.removePrefix("minecraft:") ?: "").coerceAtLeast(0)
}
