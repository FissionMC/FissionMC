package net.fissionmc.master.client

import net.fissionmc.net.ConfigException
import net.fissionmc.net.ConfigSection

/** `cross-commands:` of fission.yml: commands with a player target that move the sender to the target's backend first. */
data class CrossCommandSettings(
    val enabled: Boolean = true,
    /** `name` or `name@argIndex` (0-based, default 0). */
    val commands: List<String> = listOf("tp", "teleport"),
    /** How long the master waits for the sender's backend to answer the permission check. */
    val checkTimeoutMs: Long = 3000,
) {
    companion object {
        fun from(root: ConfigSection): CrossCommandSettings {
            val d = CrossCommandSettings(); val s = root.section("cross-commands")
            val list = s.stringList("commands").ifEmpty { d.commands }
            for (c in list) {
                val idx = c.substringAfter('@', "0")
                if (c.substringBefore('@').isBlank() || idx.toIntOrNull()?.let { it in 0..9 } != true) throw ConfigException("cross-commands.commands: '$c' is not name or name@0-9")
            }
            return CrossCommandSettings(s.bool("enabled", d.enabled), list.map { it.trim() }, s.long("check-timeout-ms", d.checkTimeoutMs))
        }
    }
}

/** `ghosts:` of fission.yml. */
data class GhostSettings(val enabled: Boolean = true, val range: Int = 128) {
    companion object {
        fun from(root: ConfigSection): GhostSettings {
            val d = GhostSettings(); val s = root.section("ghosts")
            return GhostSettings(s.bool("enabled", d.enabled), s.int("range", d.range).also { if (it !in 16..1024) throw ConfigException("ghosts.range: expected 16-1024 blocks, got $it") })
        }
    }
}

/** Recognises "command whose last argument is the name of a player on another backend" (pure, unit tested). */
class CrossCommands(settings: CrossCommandSettings) {
    class Match(val label: String, val targetName: String, val targetNode: String)
    /** Where the player named [name] is: (exact name, node) or null (offline / limbo / not found). */
    fun interface Lookup { fun find(name: String): Pair<String, String>? }

    private val argIndex: Map<String, Int> = if (!settings.enabled) emptyMap() else settings.commands.associate { c -> c.substringBefore('@').trim().lowercase().substringAfterLast(':') to (c.substringAfter('@', "0").toIntOrNull() ?: 0) }
    val enabled: Boolean get() = argIndex.isNotEmpty()

    fun match(line: String, senderNode: String?, lookup: Lookup): Match? {
        if (argIndex.isEmpty() || senderNode == null) return null
        val tokens = line.trim().removePrefix("/").split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        val label = tokens[0].lowercase().substringAfterLast(':')   // minecraft:tp, essentials:tp
        val idx = argIndex[label] ?: return null
        val args = tokens.drop(1)
        if (args.size != idx + 1) return null                        // the target is the LAST argument: `/tp Bob Alice`, `/tp Bob 1 2 3` do not move the sender
        val (name, node) = lookup.find(args[idx]) ?: return null
        if (node == senderNode) return null
        return Match(label, name, node)
    }
}
