package net.fissionmc.master

import net.fissionmc.net.NodeLink
import net.fissionmc.net.world.WorldFiles
import net.fissionmc.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * A brand-new network has no world on the master. The FIRST backend (alphabetically, among the connected ones; the holder keeps the job while it stays
 * connected) creates the world itself and uploads its identity files (level.dat, data/, datapacks/, ... everything except chunk data and local-only files),
 * the master installs them as `<world-root>/<name>`, and every other backend, which waited, then pulls them like normal. Chunks come through chunk authority.
 */
class WorldSeeding(
    private val root: Path,
    private val registry: NodeRegistry,
    private val idOf: (NodeLink) -> String?,
    private val log: (String) -> Unit = {},
) {
    private class Receive(val dir: Path) { val written = HashMap<String, Long>(); var total = 0L }
    private val holders = ConcurrentHashMap<String, String>()
    private val receiving = ConcurrentHashMap<String, Receive>()

    init {
        registry.addListener(object : NodeRegistry.Listener {
            override fun left(node: RegisteredNode, reason: String) {
                for ((world, holder) in holders) if (holder == node.info.nodeId) { holders.remove(world); receiving.remove(world)?.let { discard(it) }; log("world '$world': seeder ${node.info.nodeId} left before it finished: the job goes to the next backend that asks") }
            }
        })
    }

    private fun tmpDir(world: String): Path? = WorldFiles.resolve(root, "$world.seeding")
    private fun discard(r: Receive) { runCatching { r.dir.toFile().deleteRecursively() } }

    /** True if the message belonged to seeding (a request for a world the master does not have, or the upload). */
    fun handle(link: NodeLink, msg: NodeMessage): Boolean = when (msg) {
        is WorldRequest -> request(link, msg)
        is FileChunk -> chunk(link, msg)
        is WorldSeedDone -> done(link, msg)
        else -> false
    }

    private fun request(link: NodeLink, msg: WorldRequest): Boolean {
        val dir = WorldFiles.resolve(root, msg.name) ?: return false
        if (Files.isDirectory(dir)) return false
        val me = idOf(link) ?: return false
        val connected = (registry.all.map { it.info.nodeId } + me).toSortedSet()
        val current = holders[msg.name]
        val seeder = if (current != null && current in connected) current else connected.first().also {
            if (current != null) receiving.remove(msg.name)?.let { r -> discard(r) }
            holders[msg.name] = it
            log("world '${msg.name}' does not exist on the master: $it creates it and uploads its identity files, the other backends wait")
        }
        link.send(WorldMissing(msg.name, seeder))
        return true
    }

    private fun chunk(link: NodeLink, c: FileChunk): Boolean {
        val me = idOf(link) ?: return true
        if (holders[c.world] != me) { link.send(WorldSeeded(c.world, false, "$me is not the seeder of '${c.world}'")); return true }
        if (!WorldFiles.safeRelative(c.path) || WorldFiles.isLocalOnly(c.path)) { fail(link, c.world, "refused path ${c.path}"); return true }
        val r = receiving.computeIfAbsent(c.world) { Receive(tmpDir(c.world)!!.also { d -> d.toFile().deleteRecursively(); Files.createDirectories(d) }) }
        val file = WorldFiles.resolve(r.dir, c.path) ?: run { fail(link, c.world, "bad path ${c.path}"); return true }
        val have = r.written[c.path] ?: 0L
        if (c.offset != have) { fail(link, c.world, "${c.path}: out-of-order chunk"); return true }
        if (have + c.data.size > MAX_FILE || r.total + c.data.size > MAX_TOTAL) { fail(link, c.world, "upload too large"); return true }
        Files.createDirectories(file.parent)
        Files.write(file, c.data, *(if (have == 0L) arrayOf(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE) else arrayOf(java.nio.file.StandardOpenOption.APPEND)))
        r.written[c.path] = have + c.data.size; r.total += c.data.size
        return true
    }

    private fun done(link: NodeLink, d: WorldSeedDone): Boolean {
        val me = idOf(link) ?: return true
        if (holders[d.name] != me) { link.send(WorldSeeded(d.name, false, "$me is not the seeder of '${d.name}'")); return true }
        val r = receiving.remove(d.name)
        if (r == null || r.written.size != d.files) { fail(link, d.name, "expected ${d.files} files, received ${r?.written?.size ?: 0}"); return true }
        if (!Files.isRegularFile(r.dir.resolve("level.dat"))) { discard(r); holders.remove(d.name); link.send(WorldSeeded(d.name, false, "level.dat is missing in the upload")); return true }
        val target = WorldFiles.resolve(root, d.name)!!
        try {
            if (Files.isDirectory(target)) discard(r) else Files.move(r.dir, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) { discard(r); holders.remove(d.name); link.send(WorldSeeded(d.name, false, "cannot install the world: ${e.message}")); return true }
        holders.remove(d.name)
        log("world '${d.name}' installed from ${me}: ${d.files} files, ${r.total / 1024} KiB")
        val ok = WorldSeeded(d.name, true, "")
        registry.all.filter { it.link.isOpen }.forEach { it.link.send(ok) }
        return true
    }

    private fun fail(link: NodeLink, world: String, why: String) {
        receiving.remove(world)?.let { discard(it) }; holders.remove(world)
        log("world '$world': seeding failed: $why")
        link.send(WorldSeeded(world, false, why))
    }

    companion object { const val MAX_FILE = 64L shl 20; const val MAX_TOTAL = 256L shl 20 }
}
