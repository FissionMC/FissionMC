package net.fissionmc.net.world

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object WorldFiles {
    /** Files that are never synced. */
    private val ignored = setOf("session.lock")

    /**
     * Backend-local state that a world pull must neither delete nor overwrite, and the master never serves: the player files. The 26.x
     * layout keeps them under `players/` (`players/data/<uuid>.dat`, `players/stats`, `players/advancements`); the pre-26 folders
     * `playerdata/`, `stats/`, `advancements/` at the world root are covered too. (The master is the canonical store of player data, see
     * ; before this exclusion every backend boot wiped its own copies because the master's world had none.)
     * Any path below one of these folders is excluded, at any depth.
     */
    private val localOnlyDirs = listOf("players/", "playerdata/", "stats/", "advancements/")

    /** True for paths that belong to the backend itself and are outside the world sync (both directions). */
    fun isLocalOnly(rel: String): Boolean =
        rel.substringAfterLast('/') in ignored || localOnlyDirs.any { rel.startsWith(it) }

    /** Reject anything that could escape the world directory. */
    fun safeRelative(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("/") && !path.contains('\\') && !path.contains('\u0000') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." } && !path.contains(':')

    fun resolve(root: Path, rel: String): Path? {
        if (!safeRelative(rel)) return null
        val p = root.resolve(rel).normalize()
        return if (p.startsWith(root.normalize())) p else null
    }

    fun list(root: Path): List<String> {
        if (!Files.isDirectory(root)) return emptyList()
        Files.walk(root).use { s ->
            return s.filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) && it.fileName.toString() !in ignored }
                .map { root.relativize(it).joinToString("/") }.filter { !isLocalOnly(it) }.sorted().toList()
        }
    }

    fun sha256(file: Path): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest()
    }
}
