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

/** Master -> backend: the master has no world [name]. [seeder] (the alphabetically first connected backend id) creates it and uploads its identity files; the others wait. */
class WorldMissing(val name: String, val seeder: String) : NodeMessage
/** Backend (the seeder) -> master: all [files] identity files of the new world were sent (as [FileChunk]s); the master installs the world when they are complete. */
class WorldSeedDone(val name: String, val files: Int) : NodeMessage
/** Master -> backends: the world [name] now exists on the master ([ok]), or seeding it failed ([message]). */
class WorldSeeded(val name: String, val ok: Boolean, val message: String) : NodeMessage

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
    .register<WorldMissing>(93, { m, b -> b.writeString(m.name, 128).writeString(m.seeder, 64) }, { b -> WorldMissing(b.readString(128), b.readString(64)) })
    .register<WorldSeedDone>(94, { m, b -> b.writeString(m.name, 128).writeVarInt(m.files) }, { b -> WorldSeedDone(b.readString(128), b.readVarInt()) })
    .register<WorldSeeded>(95, { m, b -> b.writeString(m.name, 128).writeBoolean(m.ok).writeString(m.message, 512) }, { b -> WorldSeeded(b.readString(128), b.readBoolean(), b.readString(512)) })
