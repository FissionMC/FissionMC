package net.fissionmc.master

import net.fissionmc.protocol.MAX_PLAYER_DATA
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The master's canonical store of player data: one vanilla player file (gzip NBT, the bytes of a backend's `<uuid>.dat`) per UUID in [dir].
 * The master never parses it (no NMS on the master); backends load it before they create the player and send it back when they save.
 * .
 */
class PlayerDataStore(val dir: Path, private val log: (String) -> Unit = {}, private val timer: ScheduledExecutorService = defaultTimer) {
    private val owners = ConcurrentHashMap<UUID, String>()
    private val waiters = ConcurrentHashMap<UUID, MutableList<() -> Unit>>()

    init { Files.createDirectories(dir) }

    private fun file(uuid: UUID) = dir.resolve("$uuid.dat")
    private fun jsonFile(uuid: UUID, kind: Int) = dir.resolve("$uuid.${if (kind == net.fissionmc.protocol.PlayerFileKind.STATS) "stats" else "adv"}.json")

    /** The player's stats or advancements JSON as stored (null = none). */
    fun readFile(uuid: UUID, kind: Int): ByteArray? = try { jsonFile(uuid, kind).takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) } } catch (t: Throwable) { log("[playerdata] cannot read $uuid json: $t"); null }

    private fun validJson(b: ByteArray) = b.size in 2..MAX_PLAYER_DATA && String(b, 0, minOf(b.size, 64), Charsets.UTF_8).trimStart().startsWith("{")

    /** A stats/advancements file from the owning backend, replaced atomically. */
    fun onFileSave(nodeId: String, uuid: UUID, kind: Int, bytes: ByteArray): Boolean {
        val o = owners[uuid]
        if (o != null && o != nodeId) { log("[playerdata] ignored a json save of $uuid from $nodeId (owner is $o)"); return false }
        if (o == null && !Files.exists(jsonFile(uuid, kind))) { log("[playerdata] ignored a json save of $uuid from $nodeId: no backend holds this player and there is no file to update"); return false }   // a backend cannot make files for players it never had
        if (!(kind == net.fissionmc.protocol.PlayerFileKind.STATS || kind == net.fissionmc.protocol.PlayerFileKind.ADVANCEMENTS) || !validJson(bytes)) { log("[playerdata] refused a json file for $uuid (kind $kind, ${bytes.size} bytes)"); return false }
        return try {
            val tmp = Files.createTempFile(dir, "$uuid-", ".tmp"); Files.write(tmp, bytes)
            try { Files.move(tmp, jsonFile(uuid, kind), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, jsonFile(uuid, kind), StandardCopyOption.REPLACE_EXISTING) }
            true
        } catch (t: Throwable) { log("[playerdata] cannot write $uuid json: $t"); false }
    }

    /** Where the player was when it left the network (dimension, block x, block z); null = unknown. The master cannot read the player file itself (no NBT here). */
    fun readPosition(uuid: UUID): Triple<String, Int, Int>? = try {
        dir.resolve("$uuid.pos").takeIf { Files.isRegularFile(it) }?.let { Files.readString(it).trim().split(' ') }?.takeIf { it.size == 3 }?.let { Triple(it[0], it[1].toInt(), it[2].toInt()) }
    } catch (t: Throwable) { null }

    fun savePosition(uuid: UUID, dimension: String, x: Int, z: Int) {
        if (dimension.isEmpty() || dimension.any { it.isWhitespace() }) return
        try { Files.writeString(dir.resolve("$uuid.pos"), "$dimension $x $z") } catch (t: Throwable) { log("[playerdata] cannot write the position of $uuid: $t") }
    }

    fun read(uuid: UUID): ByteArray? = try { file(uuid).takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) } } catch (t: Throwable) { log("[playerdata] cannot read $uuid: $t"); null }

    /** Gzip magic and a size bound: a corrupt or hostile blob must not replace a good file. */
    fun valid(bytes: ByteArray): Boolean = bytes.size in 20..MAX_PLAYER_DATA && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /** Atomically replaces the player's file (temp file in the same folder, then move). The previous file is kept as `.dat_old`. */
    fun write(uuid: UUID, bytes: ByteArray): Boolean {
        if (!valid(bytes)) { log("[playerdata] refused ${bytes.size} bytes for $uuid (not a gzip player file)"); return false }
        return try {
            val tmp = Files.createTempFile(dir, "$uuid-", ".tmp")
            Files.write(tmp, bytes)
            val real = file(uuid)
            if (Files.exists(real)) Files.copy(real, dir.resolve("$uuid.dat_old"), StandardCopyOption.REPLACE_EXISTING)
            try { Files.move(tmp, real, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, real, StandardCopyOption.REPLACE_EXISTING) }
            true
        } catch (t: Throwable) { log("[playerdata] cannot write $uuid: $t"); false }
    }

    // ---- ownership: only the backend the player was last attached to may update the file (a late save of the backend the player left must not overwrite newer data)
    fun setOwner(uuid: UUID, nodeId: String?) { if (nodeId == null) owners.remove(uuid) else owners[uuid] = nodeId }
    fun owner(uuid: UUID): String? = owners[uuid]

    /** A save from [nodeId]. True if stored. */
    fun onSave(nodeId: String, uuid: UUID, bytes: ByteArray): Boolean {
        val o = owners[uuid]
        if (o != null && o != nodeId) { log("[playerdata] ignored a save of $uuid from $nodeId (owner is $o)"); return false }
        if (o == null && !Files.exists(file(uuid))) { log("[playerdata] ignored a save of $uuid from $nodeId: no backend holds this player and there is no file to update"); return false }   // a backend cannot make files for players it never had
        return write(uuid, bytes)
    }

    /** The backend finished a flush: everything it had to send for [uuid] has arrived (the link keeps order). */
    /** False when nobody was waiting (a late or foreign confirmation). */
    fun flushed(uuid: UUID): Boolean { val w = waiters.remove(uuid); w?.forEach { it() }; return !w.isNullOrEmpty() }

    /**
     * Runs [done] once: when the flush of [uuid] is complete ([flushed]) or after [timeoutMs]. With [onTimeout] the timeout runs that instead of [done]: the caller
     * must not go on with the stored data then (the backend's real save is still on its way and would be refused after the owner changed).
     */
    fun awaitSave(uuid: UUID, timeoutMs: Long, onTimeout: (() -> Unit)? = null, done: () -> Unit) {
        val once = AtomicBoolean()
        val run = { if (once.compareAndSet(false, true)) done() }
        waiters.computeIfAbsent(uuid) { java.util.concurrent.CopyOnWriteArrayList() }.add(run)
        timer.schedule({
            waiters.computeIfPresent(uuid) { _, l -> l.remove(run); if (l.isEmpty()) null else l }   // no empty list left behind for every timed-out flush
            if (once.compareAndSet(false, true)) {
                if (onTimeout != null) { log("[playerdata] no save of $uuid within $timeoutMs ms"); onTimeout() }
                else { log("[playerdata] no save of $uuid within $timeoutMs ms, going on with the stored data"); done() }
            }
        }, timeoutMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        val defaultTimer: ScheduledExecutorService by lazy { Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "playerdata-timer").also { it.isDaemon = true } } }
    }
}
