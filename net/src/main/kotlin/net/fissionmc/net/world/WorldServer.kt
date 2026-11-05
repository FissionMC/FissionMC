package net.fissionmc.net.world

import net.fissionmc.net.NodeLink
import net.fissionmc.protocol.*
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Serves world directories under [root] (`root/<name>/...`) to nodes. All disk work runs on its own
 * executor, never on a Netty event loop. Hashes are cached by (size, mtime).
 */
class WorldServer(private val root: Path, threads: Int = 2) : Closeable {
    private val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "world-server").apply { isDaemon = true } }
    private data class Cached(val size: Long, val mtime: Long, val hash: ByteArray)
    private val hashes = ConcurrentHashMap<Path, Cached>()

    private fun worldDir(name: String): Path? = WorldFiles.resolve(root, name)?.takeIf { Files.isDirectory(it) }

    /** Returns true if [msg] was a world message and has been handled. */
    fun handle(link: NodeLink, msg: NodeMessage): Boolean {
        when (msg) {
            is WorldRequest -> pool.execute { runCatching { manifest(link, msg.name) }.onFailure { link.send(WorldError("manifest failed: ${it.message}")) } }
            is FileRequest -> pool.execute { runCatching { stream(link, msg) }.onFailure { link.send(WorldError("read failed: ${msg.path}")) } }
            else -> return false
        }
        return true
    }

    private fun manifest(link: NodeLink, name: String) {
        val dir = worldDir(name) ?: return link.send(WorldError("no such world: $name"))
        val files = WorldFiles.list(dir).map { rel ->
            val p = dir.resolve(rel)
            val size = Files.size(p); val mtime = Files.getLastModifiedTime(p).toMillis()
            val h = hashes[p]?.takeIf { it.size == size && it.mtime == mtime }?.hash
                ?: WorldFiles.sha256(p).also { hashes[p] = Cached(size, mtime, it) }
            FileEntry(rel, size, h)
        }
        link.send(WorldManifest(name, files))
    }

    private fun stream(link: NodeLink, req: FileRequest) {
        val dir = worldDir(req.world) ?: return link.send(WorldError("no such world: ${req.world}"))
        val file = WorldFiles.resolve(dir, req.path)?.takeIf { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }
            ?: return link.send(WorldError("no such file: ${req.path}"))
        Files.newByteChannel(file).use { ch ->
            val buf = java.nio.ByteBuffer.allocate(FILE_CHUNK_SIZE)
            var offset = 0L
            while (true) {
                buf.clear()
                var n = 0
                while (buf.hasRemaining()) { val r = ch.read(buf); if (r < 0) break; n += r }
                val last = n < FILE_CHUNK_SIZE
                link.send(FileChunk(req.world, req.path, offset, buf.array().copyOf(n), last))
                offset += n
                if (last) return
                // crude backpressure: don't outrun the socket
                while (link.isOpen && !link.isWritable) Thread.sleep(2)
            }
        }
    }

    override fun close() { pool.shutdownNow() }
}
