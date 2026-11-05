package net.fissionmc.net.world

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object WorldFiles {
    /** Files that are never synced. */
    private val ignored = setOf("session.lock")

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
                .map { root.relativize(it).joinToString("/") }.sorted().toList()
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
