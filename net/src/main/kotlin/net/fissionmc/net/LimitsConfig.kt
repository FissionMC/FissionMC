package net.fissionmc.net

import net.fissionmc.protocol.Limits

/**
 * The `limits:` section of `fission.yml` (master and backend, same keys; documented in fission.default.yml / fission.backend.default.yml).
 * Sizes are bytes, or a number with a suffix (`16M`, `512K`, `1G`, also `MiB`/`KiB`/`GiB`); counts are plain numbers. Parsed by [from], put in force once at start by [apply].
 */
data class LimitsConfig(
    /** What this node accepts on the link; exchanged with the peer after the handshake (the sender uses the minimum of both). */
    val limits: Limits = Limits(),
    /** A message whose encoded size exceeds this is sent in pieces (so it cannot hold up heartbeats and chat); never above the frame limit. */
    val fragmentAbove: Int = 1 shl 20,
    /** Size of one piece. */
    val fragmentSize: Int = 256 shl 10,
    /** Bytes this node reserves for messages being reassembled, all links together. */
    val reassemblyMemory: Long = 256L shl 20,
    /** Messages being reassembled at once, per link. */
    val maxReassemblies: Int = 16,
    /** A fragmented message that is not complete this long after its first piece is dropped. */
    val reassemblyTimeoutMs: Long = 120_000,
    /** Bytes queued towards one peer (waiting pieces included) before the link is given up as stuck. */
    val maxPendingSend: Long = 512L shl 20,
    /** Plugin messages: bytes of one plugin's messages that may be on their way (queued, not yet written to the link). */
    val pluginInflight: Long = 32L shl 20,
    /** Plugin messages: messages per second one plugin may send. */
    val pluginRate: Int = 200,
) {
    companion object {
        @Volatile var active = LimitsConfig()
            private set
        /** Reassembly memory of this node, shared by all its links (sized by [active]). */
        @Volatile var reassemblyBudget = ByteBudget(LimitsConfig().reassemblyMemory)
            private set

        fun parseSize(key: String, text: String): Long {
            val m = Regex("""(\d+)\s*([kKmMgG]?)(?:i?[bB])?""").matchEntire(text.trim()) ?: throw ConfigException("limits.$key: expected a size like 16777216 or 16M, got '$text'")
            val n = m.groupValues[1].toLongOrNull() ?: throw ConfigException("limits.$key: '$text' is too large")
            val shift = when (m.groupValues[2].lowercase()) { "k" -> 10; "m" -> 20; "g" -> 30; else -> 0 }
            if (n > (Long.MAX_VALUE shr shift)) throw ConfigException("limits.$key: '$text' is too large")
            return n shl shift
        }

        fun from(root: ConfigSection): LimitsConfig {
            val s = root.section("limits"); val d = LimitsConfig(); val dl = d.limits
            fun size(k: String, def: Long): Long = s.optString(k)?.let { parseSize(k, it) } ?: def
            fun int(k: String, def: Int): Int = size(k, def.toLong()).also { if (it > Int.MAX_VALUE) throw ConfigException("limits.$k: $it is too large") }.toInt()
            fun count(k: String, def: Int) = s.int(k, def)
            try {
                val l = Limits(
                    int("frame", dl.frame), int("max-message", dl.maxMessage), int("chunk-blob", dl.chunkBlob), int("player-data", dl.playerData), int("entity-blob", dl.entityBlob),
                    count("entity-blobs", dl.entityBlobs), int("item-blob", dl.itemBlob), count("item-stacks", dl.itemStacks), int("entity-ghost-blob", dl.entityGhostBlob),
                    count("entity-ghosts", dl.entityGhosts), int("player-frame", dl.playerFrame), count("ghost-chunks", dl.ghostChunks), int("string", dl.string), int("max-plugin-message", dl.maxPluginMessage),
                )
                val c = LimitsConfig(Limits.validate(l), int("fragment-above", d.fragmentAbove), int("fragment-size", d.fragmentSize), size("reassembly-memory", d.reassemblyMemory),
                    count("max-reassemblies", d.maxReassemblies), s.long("reassembly-timeout", d.reassemblyTimeoutMs / 1000) * 1000, size("max-pending-send", d.maxPendingSend),
                    size("plugin-inflight", d.pluginInflight), count("plugin-rate", d.pluginRate))
                c.validate(); return c
            } catch (e: IllegalArgumentException) { if (e is ConfigException) throw e; throw ConfigException(e.message ?: "limits: invalid") }
        }

        /** Puts the section in force for this process (done once at start; a changed `limits:` applies after a restart). */
        fun apply(c: LimitsConfig) { Limits.setOwn(c.limits); active = c; reassemblyBudget = ByteBudget(c.reassemblyMemory) }
    }

    private fun bad(k: String, what: String): Nothing = throw ConfigException("limits.$k: $what")
    fun validate() {
        if (fragmentSize !in 4096..(16 shl 20)) bad("fragment-size", "expected 4096 to 16777216 bytes, got $fragmentSize")
        if (fragmentAbove !in 4096..limits.frame) bad("fragment-above", "expected 4096 up to limits.frame (${limits.frame}), got $fragmentAbove")
        if (fragmentSize + 1024 > limits.frame) bad("fragment-size", "$fragmentSize plus the piece header must fit in limits.frame (${limits.frame})")
        if (reassemblyMemory < limits.maxMessage || reassemblyMemory > (8L shl 30)) bad("reassembly-memory", "expected at least limits.max-message (${limits.maxMessage}) and at most 8G, got $reassemblyMemory")
        if (maxReassemblies !in 1..4096) bad("max-reassemblies", "expected 1 to 4096, got $maxReassemblies")
        if (reassemblyTimeoutMs !in 1000..3_600_000) bad("reassembly-timeout", "expected 1 to 3600 seconds, got ${reassemblyTimeoutMs / 1000}")
        if (maxPendingSend < limits.maxMessage || maxPendingSend > (16L shl 30)) bad("max-pending-send", "expected at least limits.max-message (${limits.maxMessage}) and at most 16G, got $maxPendingSend")
        if (pluginInflight < limits.maxPluginMessage || pluginInflight > (8L shl 30)) bad("plugin-inflight", "expected at least limits.max-plugin-message (${limits.maxPluginMessage}) and at most 8G, got $pluginInflight")
        if (pluginRate !in 1..1_000_000) bad("plugin-rate", "expected 1 to 1000000, got $pluginRate")
    }
}
