package net.fissionmc.net.plugins

import net.fissionmc.protocol.PLUGIN_MAX_JARS
import net.fissionmc.protocol.PLUGIN_MAX_JAR_BYTES
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Disk helpers shared by the master (listing) and the backend (local scan). */
object PluginFolder {
    private val safeName = Regex("[A-Za-z0-9._ +()\\-]{1,200}\\.jar")

    /** A replicable jar file name: a plain name, no separators, no leading dot, no "..". */
    fun safeJarName(n: String): Boolean = safeName.matches(n) && !n.startsWith(".") && !n.contains("..")

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun sha256(file: Path): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest()
    }

    /** Top-level `*.jar` regular files (not symlinks), sorted by name, within the replication limits. */
    fun listJars(dir: Path, followSymlinks: Boolean = false): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        Files.list(dir).use { s ->
            return s.filter { Files.isRegularFile(it) && (followSymlinks || !Files.isSymbolicLink(it)) && it.fileName.toString().endsWith(".jar") }
                .sorted(compareBy { it.fileName.toString() }).toList()
        }
    }

    fun withinLimits(file: Path) = safeJarName(file.fileName.toString()) && Files.size(file) <= PLUGIN_MAX_JAR_BYTES

    /** `name:` of paper-plugin.yml (preferred) or plugin.yml; "" when the jar has neither or cannot be read. */
    fun pluginName(jar: Path): String = runCatching {
        ZipFile(jar.toFile()).use { z ->
            for (entry in listOf("paper-plugin.yml", "plugin.yml")) {
                val e = z.getEntry(entry) ?: continue
                z.getInputStream(e).bufferedReader().useLines { lines ->
                    for (l in lines) {
                        val m = Regex("^name\\s*:\\s*['\"]?([^'\"#\\s]+)").find(l)
                        if (m != null) return@runCatching m.groupValues[1]
                    }
                }
            }
            ""
        }
    }.getOrDefault("")

    const val MAX_JARS = PLUGIN_MAX_JARS
}
