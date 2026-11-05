package net.fissionmc.net.world

import net.fissionmc.net.NodeLink
import net.fissionmc.protocol.*
import java.io.OutputStream
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture

data class PullResult(val downloaded: Int, val unchanged: Int, val deleted: Int, val bytes: Long)

/**
 * Backend side of the boot-time world pull: asks the master for a manifest, diffs it against [dest]
 * (size, then SHA-256), downloads what differs one file at a time, verifies hashes, and deletes
 * local files the master does not have. Feed every incoming message to [handle].
 * One pull at a time per instance.
 */
class WorldPuller(private val link: NodeLink, private val dest: Path) {
    private val result = CompletableFuture<PullResult>()
    private var world = ""
    private var queue = ArrayDeque<FileEntry>()
    private var current: FileEntry? = null
    private var out: OutputStream? = null
    private var md: MessageDigest? = null
    private var tmp: Path? = null
    private var received = 0L
    private var downloaded = 0
    private var unchanged = 0
    private var deleted = 0
    private var bytes = 0L

    fun pull(worldName: String): CompletableFuture<PullResult> {
        world = worldName
        Files.createDirectories(dest)
        link.send(WorldRequest(worldName))
        return result
    }

    /** Returns true if [msg] belonged to the pull. Runs on the link's event loop; file IO is small per chunk. */
    fun handle(msg: NodeMessage): Boolean {
        try {
            when (msg) {
                is WorldManifest -> if (msg.name == world) onManifest(msg) else return false
                is FileChunk -> if (msg.world == world) onChunk(msg) else return false
                is WorldError -> { fail(IllegalStateException(msg.message)) }
                else -> return false
            }
        } catch (t: Throwable) { fail(t) }
        return true
    }

    private fun onManifest(m: WorldManifest) {
        val want = HashSet<String>()
        for (f in m.files) {
            val target = WorldFiles.resolve(dest, f.path) ?: error("unsafe path from master: ${f.path}")
            want += f.path
            if (Files.isRegularFile(target) && Files.size(target) == f.size && WorldFiles.sha256(target).contentEquals(f.sha256)) unchanged++
            else queue += f
        }
        for (rel in WorldFiles.list(dest)) if (rel !in want) { Files.delete(dest.resolve(rel)); deleted++ }
        next()
    }

    private fun next() {
        val f = queue.removeFirstOrNull()
        if (f == null) { result.complete(PullResult(downloaded, unchanged, deleted, bytes)); return }
        current = f
        val target = WorldFiles.resolve(dest, f.path)!!
        Files.createDirectories(target.parent)
        tmp = target.resolveSibling(target.fileName.toString() + ".part")
        out = Files.newOutputStream(tmp!!)
        md = MessageDigest.getInstance("SHA-256")
        received = 0
        link.send(FileRequest(world, f.path))
    }

    private fun onChunk(c: FileChunk) {
        val f = current ?: error("unexpected chunk")
        require(c.path == f.path && c.offset == received) { "out-of-order chunk for ${c.path}" }
        received += c.data.size
        require(received <= f.size) { "${f.path} longer than manifest" }
        out!!.write(c.data); md!!.update(c.data)
        if (!c.last) return
        out!!.close(); out = null
        require(received == f.size && md!!.digest().contentEquals(f.sha256)) { "${f.path} failed verification" }
        Files.move(tmp!!, WorldFiles.resolve(dest, f.path)!!, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        downloaded++; bytes += f.size; current = null
        next()
    }

    private fun fail(t: Throwable) {
        runCatching { out?.close() }; tmp?.let { runCatching { Files.deleteIfExists(it) } }
        result.completeExceptionally(t)
    }
}
