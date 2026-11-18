package net.fissionmc.net.pairing

import net.fissionmc.net.Keys
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey

/**
 * The master's authorized keys file: one `nodeId base64PublicKey` per line (`#` comments). Read again whenever it changes on disk (so hand edits and `node add` apply at once),
 * written atomically and owner-only. A line that cannot be parsed is skipped (and reported through [warn]) instead of locking every node out.
 */
class AuthorizedKeys(val file: Path, private val warn: (String) -> Unit = {}) {
    private data class Snapshot(val mtime: Long, val size: Long, val keys: Map<String, PublicKey>)
    @Volatile private var cache: Snapshot? = null

    fun load(): Map<String, PublicKey> {
        val f = file
        if (!Files.isRegularFile(f)) return emptyMap()
        val mtime = Files.getLastModifiedTime(f).toMillis(); val size = Files.size(f)
        cache?.takeIf { it.mtime == mtime && it.size == size && System.currentTimeMillis() - mtime > 2_000 }?.let { return it.keys }   // a file changed within the last 2 s is read again (same-second edits)
        val out = LinkedHashMap<String, PublicKey>()
        for (raw in Files.readAllLines(f)) {
            val line = raw.trim(); if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2 || !PairingCode.NODE_ID.matches(parts[0])) { warn("$f: ignoring a malformed line (expected '<node id> <base64 public key>')"); continue }
            try { out[parts[0]] = Keys.decodePublic(parts[1]) } catch (e: Exception) { warn("$f: ignoring the key of '${parts[0]}': it is not a valid public key") }
        }
        cache = Snapshot(mtime, size, out)
        return out
    }

    operator fun get(id: String): PublicKey? = try { load()[id] } catch (_: Exception) { null }

    @Synchronized fun put(id: String, key: PublicKey) = edit { lines -> lines.filter { id != idOf(it) } + "$id ${Keys.encode(key)}" }

    /** True when a line was removed. */
    @Synchronized fun remove(id: String): Boolean { var found = false; edit { lines -> lines.filter { (id != idOf(it)).also { keep -> if (!keep) found = true } } }; return found }

    /** True when [from] existed and [to] did not. */
    @Synchronized fun rename(from: String, to: String): Boolean {
        val cur = this[from] ?: return false
        if (this[to] != null) return false
        edit { lines -> lines.filter { from != idOf(it) } + "$to ${Keys.encode(cur)}" }
        return true
    }

    private fun idOf(line: String): String? = line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }?.split(Regex("\\s+"), limit = 2)?.first()

    private fun edit(f: (List<String>) -> List<String>) {
        val old = if (Files.isRegularFile(file)) Files.readAllLines(file) else emptyList()
        Secrets.writeAtomic(file, f(old).joinToString("\n", postfix = "\n"))
        cache = null
    }
}
