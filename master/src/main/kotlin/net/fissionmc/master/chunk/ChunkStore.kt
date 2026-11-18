package net.fissionmc.master.chunk

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Identity of one stored blob: a chunk's data in one of the three vanilla storages (see [net.fissionmc.protocol.ChunkKind]). */
data class ChunkKey(val dimension: String, val kind: Int, val x: Int, val z: Int) {
    companion object {
        private val DIM = Regex("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}")
        /** `namespace:path` as in a resource location; no `..` segment. */
        fun validDimension(d: String) = d.length <= 129 && DIM.matches(d) && d.split(':', '/').none { it == ".." || it == "." || it.isEmpty() }
    }
}

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
    /** Writes of one chunk are serialised by a lock of its own (16 stripes by key); reads take none: a blob is always replaced as a whole (temp file + atomic move), so a reader sees the old or the new one. One global lock made every request wait behind the fsync of any push. */
    private val stripes = Array(16) { Any() }
    private fun stripe(k: ChunkKey): Any = stripes[(k.hashCode() and 0x7fffffff) % stripes.size]
    private val versionLock = Any()
    private val counterFile = dir.resolve("version.counter")
    private var next: Long
    private var persisted: Long
    @Volatile private var total = -1L   // counted under versionLock

    init {
        Files.createDirectories(dir)
        // after a crash the counter restarts above every version handed out (the file is always ahead by the safety margin)
        persisted = runCatching { Files.readString(counterFile).trim().toLong() }.getOrDefault(0L)
        next = persisted
    }

    private fun kindName(kind: Int) = when (kind) { 0 -> "chunk"; 1 -> "entities"; 2 -> "poi"; else -> "kind$kind" }

    private fun file(k: ChunkKey): Path =
        dir.resolve(k.dimension.replace(':', '_').replace('/', '_')).resolve(kindName(k.kind)).resolve("r.${k.x shr 5}.${k.z shr 5}").resolve("c.${k.x}.${k.z}.fch")

    override fun get(key: ChunkKey): StoredChunk? = if (!ChunkKey.validDimension(key.dimension)) null else read(key)

    private fun read(key: ChunkKey): StoredChunk? {
        val f = file(key)
        if (!Files.isRegularFile(f)) return null
        return try {
            val size = Files.size(f)
            DataInputStream(Files.newInputStream(f).buffered()).use { i ->
                if (i.readInt() != MAGIC) return quarantine(f, "bad magic")
                val status = i.readInt(); val version = i.readLong(); val n = i.readInt()
                if (n < 0 || n > size) return quarantine(f, "bad length $n in a file of $size bytes")   // no giant allocation for a corrupt length
                val b = ByteArray(n); i.readFully(b)
                val writer = runCatching { i.readUTF() }.getOrDefault("")
                StoredChunk(status, version, b, writer)
            }
        } catch (t: java.io.EOFException) { quarantine(f, "truncated")
        } catch (t: Throwable) { log("[chunks] unreadable $f: $t"); null }
    }

    /** A damaged file is moved aside (`.bad-<time>`), never overwritten by the next push: the bytes may still be recoverable. The chunk then counts as absent. */
    private fun quarantine(f: Path, why: String): StoredChunk? {
        quarantinedInc()
        val bad = f.resolveSibling(f.fileName.toString() + ".bad-" + System.currentTimeMillis())
        try { Files.move(f, bad, StandardCopyOption.REPLACE_EXISTING); log("[chunks] damaged chunk file $f ($why): moved to ${bad.fileName}") }
        catch (t: Throwable) { log("[chunks] damaged chunk file $f ($why), cannot move it aside: $t") }
        return null
    }
    /** Damaged chunk files moved aside since start. */
    @Volatile var quarantined = 0L; private set
    private fun quarantinedInc() { synchronized(versionLock) { quarantined++ } }

    private fun nextVersion(): Long = synchronized(versionLock) {
        next++
        if (next >= persisted) { persisted = next + 1000; Files.writeString(counterFile, persisted.toString()) }
        next
    }
    private fun counted() = synchronized(versionLock) { if (total >= 0) total++ }

    private fun write(key: ChunkKey, status: Int, bytes: ByteArray, writer: String): Long {
        val version = nextVersion()
        val f = file(key); Files.createDirectories(f.parent)
        val tmp = Files.createTempFile(f.parent, "c-", ".tmp")
        java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.WRITE).use { ch ->
            val buf = java.io.ByteArrayOutputStream(bytes.size + 64)
            DataOutputStream(buf).use { o -> o.writeInt(MAGIC); o.writeInt(status); o.writeLong(version); o.writeInt(bytes.size); o.write(bytes); o.writeUTF(writer) }
            val bb = java.nio.ByteBuffer.wrap(buf.toByteArray()); while (bb.hasRemaining()) ch.write(bb)
            ch.force(true)   // on disk before the move: a crash must not leave a zero-length file under the final name
        }
        try { Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING) }
        return version
    }

    override fun put(key: ChunkKey, status: Int, baseVersion: Long, bytes: ByteArray, writer: String): PutResult = synchronized(stripe(key)) {
        require(ChunkKey.validDimension(key.dimension)) { "invalid level id" }
        val cur = read(key)
        val ok = cur == null || status > cur.status || (status >= cur.status && (baseVersion == cur.version || writer == cur.writer))
        if (!ok) return PutResult(false, cur!!.version)
        if (cur == null) counted()
        PutResult(true, write(key, status, bytes, writer))
    }

    override fun putIfBetter(key: ChunkKey, status: Int, bytes: ByteArray): PutResult = synchronized(stripe(key)) {
        val cur = read(key)
        if (cur != null && status <= cur.status) return PutResult(false, cur.version)
        if (cur == null) counted()
        PutResult(true, write(key, status, bytes, "import"))
    }

    override fun count(): Long {
        if (total >= 0) return total
        total = if (Files.isDirectory(dir)) Files.walk(dir).use { s -> s.filter { it.fileName.toString().endsWith(".fch") }.count() } else 0
        return total
    }

    companion object { const val MAGIC = 0x46434831 }
}
