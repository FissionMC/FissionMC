package net.fissionmc.protocol

import io.netty.buffer.ByteBuf

/** One file of a world: path is relative, '/'-separated, never absolute or containing "..". */
class FileEntry(val path: String, val size: Long, val sha256: ByteArray)

class WorldRequest(val name: String) : NodeMessage
class WorldManifest(val name: String, val files: List<FileEntry>) : NodeMessage
class FileRequest(val world: String, val path: String) : NodeMessage
class FileChunk(val world: String, val path: String, val offset: Long, val data: ByteArray, val last: Boolean) : NodeMessage
class WorldError(val message: String) : NodeMessage

/** Max payload per [FileChunk]; keeps frames far below the 8 MiB frame limit. */
const val FILE_CHUNK_SIZE = 256 * 1024

/** Registers world-sync messages (ids 16-31). */
fun MessageRegistry.registerWorldMessages(): MessageRegistry = this
    .register<WorldRequest>(16, { m, b -> b.writeString(m.name, 128) }, { b -> WorldRequest(b.readString(128)) })
    .register<WorldManifest>(17,
        { m, b ->
            b.writeString(m.name, 128).writeVarInt(m.files.size)
            for (f in m.files) b.writeString(f.path, 1024).writeLong(f.size).writeByteArray(f.sha256, 32)
        },
        { b ->
            val name = b.readString(128)
            val n = b.readVarInt()
            require(n in 0..1_000_000) { "Bad file count $n" }
            WorldManifest(name, List(n) { FileEntry(b.readString(1024), b.readLong(), b.readByteArray(32)) })
        })
    .register<FileRequest>(18, { m, b -> b.writeString(m.world, 128).writeString(m.path, 1024) }, { b -> FileRequest(b.readString(128), b.readString(1024)) })
    .register<FileChunk>(19,
        { m, b -> b.writeString(m.world, 128).writeString(m.path, 1024).writeLong(m.offset).writeByteArray(m.data, FILE_CHUNK_SIZE).writeBoolean(m.last) },
        { b -> FileChunk(b.readString(128), b.readString(1024), b.readLong(), b.readByteArray(FILE_CHUNK_SIZE), b.readBoolean()) })
    .register<WorldError>(20, { m, b -> b.writeString(m.message, 512) }, { b -> WorldError(b.readString(512)) })
