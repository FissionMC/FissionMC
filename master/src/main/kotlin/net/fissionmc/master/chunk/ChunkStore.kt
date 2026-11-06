package net.fissionmc.master.chunk

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Identity of one stored blob: a chunk's data in one of the three vanilla storages (see [net.fissionmc.protocol.ChunkKind]). */
data class ChunkKey(val dimension: String, val kind: Int, val x: Int, val z: Int)

/** [bytes] = zlib(NbtIo.write(chunk tag)), the payload of a region file slot; the master never parses it. */
class StoredChunk(val status: Int, val version: Long, val bytes: ByteArray, val writer: String = "")

data class PutResult(val accepted: Boolean, val version: Long)

/** The master's canonical chunk data. Behind an interface so an MCA or section KV implementation can replace the files later. */
interface ChunkStore {
    fun get(key: ChunkKey): StoredChunk?

    /**
     * A chunk from backend [writer]. Accepted when nothing is stored, when [status] is higher than the stored one, or when it is not lower and either
     * [baseVersion] equals the stored version (the sender updated what it had loaded: compare and set) or the same backend wrote the stored version (a backend's
     * quick successive saves need not wait for each acknowledgement). Returns the new version, or the stored one if refused.
     */
    fun put(key: ChunkKey, status: Int, baseVersion: Long, bytes: ByteArray, writer: String): PutResult

    /** Import rule: higher status wins, equal status keeps the first one. */
    fun putIfBetter(key: ChunkKey, status: Int, bytes: ByteArray): PutResult

    fun count(): Long
}

/** One file per blob: `<dir>/<dimension>/<kind>/r.<rx>.<rz>/c.<x>.<z>.fch`, written atomically (temp file, move). */
class FileChunkStore(private val dir: Path, private val log: (String) -> Unit = {}) : ChunkStore {
    private val lock = Any()
    private val counterFile = dir.resolve("version.counter")
    private var next: Long
    private var persisted: Long
    @Volatile private var total = -1L

    init {
        Files.createDirectories(dir)
        // after a crash the counter restarts above every version handed out (the file is always ahead by the safety margin)
        persisted = runCatching { Files.readString(counterFile).trim().toLong() }.getOrDefault(0L)
        next = persisted
    }

    private fun kindName(kind: Int) = when (kind) { 0 -> "chunk"; 1 -> "entities"; 2 -> "poi"; else -> "kind$kind" }

    private fun file(k: ChunkKey): Path =
        dir.resolve(k.dimension.replace(':', '_').replace('/', '_')).resolve(kindName(k.kind)).resolve("r.${k.x shr 5}.${k.z shr 5}").resolve("c.${k.x}.${k.z}.fch")

    override fun get(key: ChunkKey): StoredChunk? = synchronized(lock) { read(key) }

    private fun read(key: ChunkKey): StoredChunk? {
        val f = file(key)
        if (!Files.isRegularFile(f)) return null
        return try {
            DataInputStream(Files.newInputStream(f).buffered()).use { i ->
                if (i.readInt() != MAGIC) return null
                val status = i.readInt(); val version = i.readLong(); val n = i.readInt()
                val b = ByteArray(n); i.readFully(b)
                val writer = runCatching { i.readUTF() }.getOrDefault("")
                StoredChunk(status, version, b, writer)
            }
        } catch (t: Throwable) { log("[chunks] unreadable $f: $t"); null }
    }

    private fun nextVersion(): Long {
        next++
        if (next >= persisted) { persisted = next + 1000; Files.writeString(counterFile, persisted.toString()) }
        return next
    }

    private fun write(key: ChunkKey, status: Int, bytes: ByteArray, writer: String): Long {
        val version = nextVersion()
        val f = file(key); Files.createDirectories(f.parent)
        val tmp = Files.createTempFile(f.parent, "c-", ".tmp")
        DataOutputStream(Files.newOutputStream(tmp).buffered()).use { o -> o.writeInt(MAGIC); o.writeInt(status); o.writeLong(version); o.writeInt(bytes.size); o.write(bytes); o.writeUTF(writer) }
        try { Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING) }
        return version
    }

    override fun put(key: ChunkKey, status: Int, baseVersion: Long, bytes: ByteArray, writer: String): PutResult = synchronized(lock) {
        val cur = read(key)
        val ok = cur == null || status > cur.status || (status >= cur.status && (baseVersion == cur.version || writer == cur.writer))
        if (!ok) return PutResult(false, cur!!.version)
        if (cur == null && total >= 0) total++
        PutResult(true, write(key, status, bytes, writer))
    }

    override fun putIfBetter(key: ChunkKey, status: Int, bytes: ByteArray): PutResult = synchronized(lock) {
        val cur = read(key)
        if (cur != null && status <= cur.status) return PutResult(false, cur.version)
        if (cur == null && total >= 0) total++
        PutResult(true, write(key, status, bytes, "import"))
    }

    override fun count(): Long {
        if (total >= 0) return total
        total = if (Files.isDirectory(dir)) Files.walk(dir).use { s -> s.filter { it.fileName.toString().endsWith(".fch") }.count() } else 0
        return total
    }

    companion object { const val MAGIC = 0x46434831 }
}
