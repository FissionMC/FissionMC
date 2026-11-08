package net.fissionmc.net.plugins

import net.fissionmc.net.ConfigException
import net.fissionmc.net.ConfigSection

enum class ConflictPolicy { LOCAL, MASTER }

/**
 * The `plugins:` section of `fission.yml`. Master: `replicate` (serve its jars) and `dir` (the folder, relative to the file).
 * Backend: `replicate` (pull at boot) and `conflict` (a backend-local jar with the same plugin wins: `local`, or loses: `master`).
 */
data class PluginsConfig(val replicate: Boolean = true, val dir: String = "plugins", val conflict: ConflictPolicy = ConflictPolicy.LOCAL) {
    companion object {
        fun from(root: ConfigSection): PluginsConfig {
            val s = root.section("plugins")
            val dir = s.string("dir", "plugins").trim().ifBlank { "plugins" }
            val c = s.string("conflict", "local").trim().lowercase()
            val policy = ConflictPolicy.entries.firstOrNull { it.name.lowercase() == c } ?: throw ConfigException("plugins.conflict: expected local or master, got '$c'")
            return PluginsConfig(s.bool("replicate", true), dir, policy)
        }
    }
}
