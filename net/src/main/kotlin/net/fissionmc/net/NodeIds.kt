package net.fissionmc.net

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * Backend ids. A `node.id` written by hand always wins and is never touched. Without one the backend generates `backend-` + 6 random hex characters on its first start and
 * SAVES it, because everything keys on the id (event log resume, player data ownership, chunk leases, the master's authorized keys): it must be the same after a restart.
 * Saved into `fission.yml` under `node:` by a text edit (comments stay; the file is never re-dumped), or, when the section is written in flow style / the file is read-only
 * / combined mode, into `.fission-node-id` next to it.
 */
object NodeIds {
    class Resolved(val id: String, val generated: Boolean, val savedTo: Path?)

    private val valid = Regex("[A-Za-z0-9._-]{1,48}")
    fun random(rng: SecureRandom = SecureRandom()): String = "backend-" + ByteArray(3).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    /**
     * [configured] is `node.id` of the file ([configFile]) or null. [persistInYml] false = always use the side file (combined mode).
     * Returns the id to use and where a newly generated one was saved (null when it already existed).
     */
    fun resolve(configFile: Path, configured: String?, persistInYml: Boolean = true, rng: SecureRandom = SecureRandom()): Resolved {
        if (!configured.isNullOrBlank()) return Resolved(configured.trim(), false, null)
        val side = configFile.toAbsolutePath().resolveSibling(".fission-node-id")
        if (Files.isRegularFile(side)) Files.readString(side).trim().takeIf { valid.matches(it) }?.let { return Resolved(it, false, null) }
        val id = random(rng)
        if (persistInYml && insertIntoYml(configFile, id)) return Resolved(id, true, configFile)
        Files.writeString(side, id + "\n")
        return Resolved(id, true, side)
    }

    /** Adds `  id: "<id>"` as the first line of a block style `node:` section (replacing an empty `id:`); false when that is not possible. */
    internal fun insertIntoYml(file: Path, id: String): Boolean = try {
        val lines = Files.readAllLines(file).toMutableList()
        val node = lines.indexOfFirst { it.matches(Regex("^node:\\s*(#.*)?$")) }
        if (node < 0) false else {
            val note = "  id: \"$id\"   # generated on the first start and saved here: keep it (the master's authorized keys and the event log use it), or write your own"
            val existing = (node + 1 until lines.size).takeWhile { lines[it].isBlank() || lines[it].startsWith(" ") || lines[it].startsWith("\t") || lines[it].startsWith("#") }
                .firstOrNull { lines[it].matches(Regex("^\\s+id:\\s*(\"\")?(''\\s*)?(#.*)?$")) }
            if (existing != null) lines[existing] = note else lines.add(node + 1, note)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.write(tmp, lines + "")
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            true
        }
    } catch (e: Exception) { false }
}
