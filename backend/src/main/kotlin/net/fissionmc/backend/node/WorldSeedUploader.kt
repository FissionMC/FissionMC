package net.fissionmc.backend.node

import net.fissionmc.net.world.WorldFiles
import net.fissionmc.protocol.FILE_CHUNK_SIZE
import net.fissionmc.protocol.FileChunk
import net.fissionmc.protocol.WorldSeedDone
import net.fissionmc.protocol.WorldSeeded
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/**
 * The seeder's half of "the master has no world": once this backend has created its world (level.dat exists, a few seconds after start),
 * it uploads every identity file (everything except chunk data and local-only files) so the master can install the world for the other backends.
 */
object WorldSeedUploader {
    private val log = Logger.getLogger("FissionMC")
    @Volatile private var dir: Path? = null
    @Volatile private var name = ""
    @Volatile private var uploading = false
    private var ticks = 0

    fun arm(worldDir: Path, levelName: String) { dir = worldDir; name = levelName }

    /** Server thread, every tick: starts the upload once the world files exist. */
    @JvmStatic fun tick() {
        val d = dir ?: return
        if (uploading || ++ticks < 200 || !Files.isRegularFile(d.resolve("level.dat"))) return
        val link = NodeBootstrap.link?.takeIf { it.isOpen } ?: return
        uploading = true
        Thread({
            try {
                val files = WorldFiles.list(d)
                for (rel in files) {
                    val bytes = Files.readAllBytes(d.resolve(rel))
                    var off = 0
                    do {
                        val n = minOf(FILE_CHUNK_SIZE, bytes.size - off)
                        link.send(FileChunk(name, rel, off.toLong(), bytes.copyOfRange(off, off + n), off + n >= bytes.size))
                        off += n
                        while (link.isOpen && !link.isWritable) Thread.sleep(2)
                    } while (off < bytes.size)
                }
                link.send(WorldSeedDone(name, files.size))
                log.info("[fission] uploaded the world '$name' (${files.size} files) to the master")
            } catch (t: Throwable) { log.warning("[fission] world upload failed: $t"); uploading = false; ticks = 0 }
        }, "world-seed-upload").also { it.isDaemon = true }.start()
    }

    /** The master's answer: installed (every backend may pull now) or refused (tried again later). */
    fun onSeeded(m: WorldSeeded) {
        if (m.name != name) return
        if (m.ok) { log.info("[fission] the master installed the world '$name'"); dir = null }
        else { log.warning("[fission] the master refused the world '$name': ${m.message}; will retry"); uploading = false; ticks = 0 }
    }
}
