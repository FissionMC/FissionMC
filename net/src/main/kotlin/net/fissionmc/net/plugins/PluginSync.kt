package net.fissionmc.net.plugins

import net.fissionmc.protocol.PluginEntry
import net.fissionmc.protocol.PLUGIN_MAX_JARS
import net.fissionmc.protocol.PLUGIN_MAX_JAR_BYTES

/** A jar present in the backend's plugins folder: [sha] is lowercase hex. */
data class FolderJar(val file: String, val sha: String, val pluginName: String)

/** A jar replication installed (and so may replace or delete). */
data class ManagedJar(val file: String, val sha: String, val pluginName: String)

/** A backend-local jar replication renamed to `<file>.fission-shadowed` under policy `master`. */
data class ShadowedJar(val file: String, val pluginName: String)

/** The persisted ownership record (`plugins/.fission-managed.txt`). Only files listed here may ever be replaced or deleted by replication. */
data class ManagedState(val jars: List<ManagedJar> = emptyList(), val shadows: List<ShadowedJar> = emptyList()) {
    fun serialize(): String = buildString {
        append("# Written by FissionMC plugin replication. Jars listed here came from the master and are replaced/removed with it; every other jar is backend-local and never touched.\n")
        jars.sortedBy { it.file }.forEach { append("jar\t${it.file}\t${it.sha}\t${it.pluginName}\n") }
        shadows.sortedBy { it.file }.forEach { append("shadow\t${it.file}\t${it.pluginName}\n") }
    }

    companion object {
        fun parse(text: String): ManagedState {
            val jars = ArrayList<ManagedJar>(); val sh = ArrayList<ShadowedJar>()
            for (l in text.lines()) {
                if (l.isBlank() || l.startsWith("#")) continue
                val p = l.split('\t')
                when {
                    p[0] == "jar" && p.size >= 3 && PluginFolder.safeJarName(p[1]) -> jars += ManagedJar(p[1], p[2], p.getOrElse(3) { "" })
                    p[0] == "shadow" && p.size >= 2 && PluginFolder.safeJarName(p[1]) -> sh += ShadowedJar(p[1], p.getOrElse(2) { "" })
                }
            }
            return ManagedState(jars, sh)
        }
    }
}

/** What to do to the backend's plugins folder. All name lists are plain file names. */
data class SyncPlan(
    val download: List<PluginEntry>,
    val unchanged: List<String>,
    /** stale managed jars (no longer on the master, or lost a conflict) */
    val delete: List<String>,
    /** master entries not installed, with the reason (conflict with a local jar, unsafe name, too large, duplicate plugin name) */
    val skipped: List<Pair<String, String>>,
    /** local jars to rename to `.fission-shadowed` (policy master) */
    val shadow: List<ShadowedJar>,
    /** shadowed jars to restore */
    val unshadow: List<String>,
    /** the ownership record after the plan is applied */
    val state: ManagedState,
)

object PluginSync {
    /**
     * Pure planning step. [folder] = every `*.jar` currently in the backend's plugins folder (managed and local, with hashes and plugin names);
     * [managed] = the persisted ownership record. A jar is backend-local iff it is not in [managed]; local jars are never deleted or replaced:
     * under `master`, a conflicting local jar is renamed (reversible), never removed.
     */
    fun plan(manifest: List<PluginEntry>, folder: List<FolderJar>, managed: ManagedState, policy: ConflictPolicy): SyncPlan {
        val managedNames = managed.jars.map { it.file }.toSet()
        val onDisk = folder.associateBy { it.file }
        val local = folder.filter { it.file !in managedNames }
        val download = ArrayList<PluginEntry>(); val unchanged = ArrayList<String>(); val skipped = ArrayList<Pair<String, String>>()
        val shadowNew = LinkedHashMap<String, ShadowedJar>(); val installed = ArrayList<ManagedJar>(); val seenPlugins = HashSet<String>()
        fun sameName(a: String, b: String) = a.isNotBlank() && a.equals(b, ignoreCase = true)

        for (e in manifest.take(PLUGIN_MAX_JARS).sortedBy { it.file }) {
            if (!PluginFolder.safeJarName(e.file)) { skipped += e.file to "unsafe file name"; continue }
            if (e.size > PLUGIN_MAX_JAR_BYTES || e.size < 0) { skipped += e.file to "larger than the limit"; continue }
            if (e.pluginName.isNotBlank() && !seenPlugins.add(e.pluginName.lowercase())) { skipped += e.file to "second jar of plugin ${e.pluginName} on the master"; continue }
            val clash = local.filter { it.file == e.file || sameName(it.pluginName, e.pluginName) }
            if (clash.isNotEmpty()) {
                if (policy == ConflictPolicy.LOCAL) { skipped += e.file to "backend-local ${clash.joinToString { it.file }} wins (plugins.conflict: local)"; continue }
                clash.forEach { shadowNew[it.file] = ShadowedJar(it.file, it.pluginName) }
            }
            val have = onDisk[e.file]
            if (e.file in managedNames && have != null && have.sha == PluginFolder.hex(e.sha256)) unchanged += e.file else download += e
            installed += ManagedJar(e.file, PluginFolder.hex(e.sha256), e.pluginName)
        }
        val keep = installed.map { it.file }.toSet()
        val delete = managed.jars.map { it.file }.filter { it !in keep }
        // previously shadowed local jars: restore when no installed master jar clashes with them any more
        val unshadow = ArrayList<String>(); val stillShadowed = ArrayList<ShadowedJar>()
        for (s in managed.shadows) {
            val needed = installed.any { it.file == s.file || sameName(it.pluginName, s.pluginName) }
            if (needed || onDisk.containsKey(s.file) && s.file !in delete) stillShadowed += s else unshadow += s.file
        }
        val shadowFinal = shadowNew.values.filter { s -> stillShadowed.none { it.file == s.file } }
        return SyncPlan(download, unchanged, delete, skipped, shadowFinal, unshadow, ManagedState(installed, stillShadowed + shadowFinal))
    }
}
