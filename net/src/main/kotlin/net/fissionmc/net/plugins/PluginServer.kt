package net.fissionmc.net.plugins

import net.fissionmc.net.NodeLink
import net.fissionmc.protocol.*
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Master side of plugin replication: serves the top-level jars of [dir]. Disk work runs on its own executor. */
class PluginServer(private val dir: Path, private val enabled: Boolean = true, private val log: (String) -> Unit = {}) : Closeable {
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "plugin-server").apply { isDaemon = true } }
    private data class Cached(val size: Long, val mtime: Long, val hash: ByteArray, val name: String)
    private val cache = ConcurrentHashMap<Path, Cached>()

    fun handle(link: NodeLink, msg: NodeMessage): Boolean {
        when (msg) {
            is PluginManifestRequest -> pool.execute { runCatching { manifest(link) }.onFailure { link.send(PluginSyncError("manifest failed: ${it.message}")) } }
            is PluginFileRequest -> pool.execute { runCatching { stream(link, msg.file) }.onFailure { link.send(PluginSyncError("read failed: ${msg.file}")) } }
            else -> return false
        }
        return true
    }

    /** The jars that are offered: safe name, regular file, size limit, at most [PLUGIN_MAX_JARS]. */
    fun entries(): List<PluginEntry> {
        if (!enabled) return emptyList()
        val out = ArrayList<PluginEntry>()
        for (p in PluginFolder.listJars(dir)) {
            if (!PluginFolder.withinLimits(p)) { log("plugin replication: ignoring ${p.fileName} (unsafe name or larger than the limit)"); continue }
            if (out.size >= PLUGIN_MAX_JARS) { log("plugin replication: more than $PLUGIN_MAX_JARS jars, the rest is ignored"); break }
            val size = Files.size(p); val mtime = Files.getLastModifiedTime(p).toMillis()
            val c = cache[p]?.takeIf { it.size == size && it.mtime == mtime } ?: Cached(size, mtime, PluginFolder.sha256(p), PluginFolder.pluginName(p)).also { cache[p] = it }
            out += PluginEntry(p.fileName.toString(), size, c.hash, c.name.take(128))
        }
        return out
    }

    private fun manifest(link: NodeLink) {
        val e = entries()
        log("plugin replication: manifest of ${e.size} jar(s) sent" + if (!enabled) " (plugins.replicate is off)" else "")
        link.send(PluginManifest(enabled, e))
    }

    private fun stream(link: NodeLink, name: String) {
        val file = dir.resolve(name).takeIf { enabled && PluginFolder.safeJarName(name) && Files.isRegularFile(it) && !Files.isSymbolicLink(it) && PluginFolder.withinLimits(it) }
            ?: return link.send(PluginSyncError("no such plugin jar: $name"))
        Files.newByteChannel(file).use { ch ->
            val buf = java.nio.ByteBuffer.allocate(FILE_CHUNK_SIZE)
            var offset = 0L
            while (true) {
                buf.clear()
                var n = 0
                while (buf.hasRemaining()) { val r = ch.read(buf); if (r < 0) break; n += r }
                val last = n < FILE_CHUNK_SIZE
                link.send(PluginFileChunk(name, offset, buf.array().copyOf(n), last))
                offset += n
                if (last) return
                while (link.isOpen && !link.isWritable) Thread.sleep(2)
            }
        }
    }

    override fun close() { pool.shutdownNow() }
}
