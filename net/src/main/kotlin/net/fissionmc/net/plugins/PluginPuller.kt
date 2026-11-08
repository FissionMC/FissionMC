package net.fissionmc.net.plugins

import net.fissionmc.net.NodeLink
import net.fissionmc.protocol.*
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture

data class PluginSyncResult(
    val enabled: Boolean, val installed: List<String>, val unchanged: List<String>, val deleted: List<String>,
    val skipped: List<Pair<String, String>>, val shadowed: List<String>, val restored: List<String>, val failed: List<String>,
) {
    /** jars that came from the master and are in the folder now */
    val replicated: List<String> get() = (installed + unchanged).sorted()
}

/**
 * Backend side: pulls the master's plugin jars into [dir] (Paper's plugins folder) before plugin discovery.
 * One pull per instance; feed incoming messages to [handle]. A failure while downloading one jar leaves the previous file (if any) and goes on.
 * Backend-local jars (anything not in `.fission-managed.txt`) are never deleted or replaced.
 */
class PluginPuller(private val link: NodeLink, private val dir: Path, private val policy: ConflictPolicy, private val log: (String) -> Unit = {}) {
    private val result = CompletableFuture<PluginSyncResult>()
    private var plan: SyncPlan? = null
    private var queue = ArrayDeque<PluginEntry>()
    private var current: PluginEntry? = null
    private var out: OutputStream? = null
    private var md: MessageDigest? = null
    private var tmp: Path? = null
    private var received = 0L
    private val installed = ArrayList<String>(); private val failed = ArrayList<String>()
    private var shadowed = emptyList<String>(); private var restored = emptyList<String>()

    fun pull(): CompletableFuture<PluginSyncResult> {
        Files.createDirectories(dir)
        link.send(PluginManifestRequest())
        return result
    }

    fun handle(msg: NodeMessage): Boolean {
        try {
            when (msg) {
                is PluginManifest -> onManifest(msg)
                is PluginFileChunk -> onChunk(msg)
                is PluginSyncError -> if (current != null) { failCurrent(msg.message); next() } else result.completeExceptionally(IllegalStateException(msg.message))
                else -> return false
            }
        } catch (t: Throwable) { runCatching { out?.close() }; tmp?.let { runCatching { Files.deleteIfExists(it) } }; result.completeExceptionally(t) }
        return true
    }

    private fun onManifest(m: PluginManifest) {
        val state = readState(dir)
        if (!m.enabled) {
            log("the master does not replicate plugins (plugins.replicate: false); ${state.jars.size} previously replicated jar(s) are left as they are")
            result.complete(PluginSyncResult(false, emptyList(), state.jars.map { it.file }, emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
            return
        }
        require(m.files.size <= PLUGIN_MAX_JARS) { "master offers ${m.files.size} jars" }
        val folder = PluginFolder.listJars(dir, followSymlinks = true).map { p ->
            val managed = state.jars.firstOrNull { it.file == p.fileName.toString() }
            // local jars are only read for their plugin name; managed ones are hashed to detect edits/corruption
            FolderJar(p.fileName.toString(), PluginFolder.hex(PluginFolder.sha256(p)), managed?.pluginName ?: PluginFolder.pluginName(p))
        }
        val p = PluginSync.plan(m.files, folder, state, policy)
        plan = p
        for ((f, why) in p.skipped) log("plugin $f from the master not installed: $why")
        // ownership first: if the process dies mid-way the next boot finds the intended record, hashes the disk and re-downloads what differs
        writeState(dir, p.state)
        for (f in p.delete) { Files.deleteIfExists(dir.resolve(f)); log("removed replicated plugin $f (no longer on the master)") }
        for (f in p.unshadow) { Files.move(dir.resolve("$f$SHADOW"), dir.resolve(f), StandardCopyOption.ATOMIC_MOVE); log("restored backend-local plugin $f") }
        restored = p.unshadow
        for (s in p.shadow) { Files.move(dir.resolve(s.file), dir.resolve(s.file + SHADOW), StandardCopyOption.REPLACE_EXISTING); log("backend-local plugin ${s.file} shadowed by the master's plugin ${s.pluginName} (plugins.conflict: master; renamed to ${s.file}$SHADOW)") }
        shadowed = p.shadow.map { it.file }
        queue = ArrayDeque(p.download)
        next()
    }

    private fun next() {
        val e = queue.removeFirstOrNull()
        if (e == null) { finish(); return }
        current = e
        tmp = dir.resolve(e.file + ".part")
        out = Files.newOutputStream(tmp!!)
        md = MessageDigest.getInstance("SHA-256")
        received = 0
        link.send(PluginFileRequest(e.file))
    }

    private fun onChunk(c: PluginFileChunk) {
        val e = current ?: error("unexpected plugin chunk")
        require(c.file == e.file && c.offset == received) { "out-of-order chunk for ${c.file}" }
        received += c.data.size
        if (received > e.size) { failCurrent("longer than the manifest says"); next(); return }
        out!!.write(c.data); md!!.update(c.data)
        if (!c.last) return
        out!!.close(); out = null
        if (received != e.size || !md!!.digest().contentEquals(e.sha256)) { failCurrent("failed hash verification"); next(); return }
        Files.move(tmp!!, dir.resolve(e.file), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        installed += e.file; current = null
        log("replicated plugin ${e.file} (${e.pluginName.ifBlank { "?" }}, ${e.size / 1024} KiB)")
        next()
    }

    private fun failCurrent(why: String) {
        val e = current ?: return
        runCatching { out?.close() }; out = null
        tmp?.let { runCatching { Files.deleteIfExists(it) } }
        failed += e.file; current = null
        log("plugin ${e.file} could not be replicated: $why")
    }

    private fun finish() {
        val p = plan!!
        result.complete(PluginSyncResult(true, installed, p.unchanged, p.delete, p.skipped, shadowed, restored, failed))
    }

    companion object {
        const val STATE_FILE = ".fission-managed.txt"
        const val SHADOW = ".fission-shadowed"
        fun readState(dir: Path): ManagedState = dir.resolve(STATE_FILE).let { if (Files.isRegularFile(it)) ManagedState.parse(Files.readString(it)) else ManagedState() }
        fun writeState(dir: Path, s: ManagedState) {
            val f = dir.resolve(STATE_FILE); val t = dir.resolve("$STATE_FILE.tmp")
            Files.writeString(t, s.serialize()); Files.move(t, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
