package net.fissionmc.master.safety

import net.fissionmc.net.ConfigException
import net.fissionmc.net.ConfigSection

/** What to do with a backend packet that a vanilla client cannot take and that cannot be fixed in place. */
enum class Policy { DROP, DISCONNECT, EMPTY;
    companion object { fun of(key: String, text: String, allowed: Set<Policy>): Policy =
        values().firstOrNull { it.name.equals(text.trim(), true) }?.takeIf { it in allowed } ?: throw ConfigException("$key: expected ${allowed.joinToString(" | ") { it.name.lowercase() }}, got '$text'") }
}

/**
 * The `safety:` section of the master's `fission.yml`: the client safety layer (`.dev/docs/design/client-safety.md`). Everything defaults to on, with limits at or below what a vanilla 26.3
 * client accepts, so data a vanilla server could legitimately send is never touched.
 */
data class SafetyConfig(
    val enabled: Boolean = true,
    /** Largest clientbound frame (packet id + payload, uncompressed) forwarded. Vanilla: 8 MiB uncompressed, and 2 MiB - 1 compressed (checked by really compressing frames over 1.5 MiB). */
    val maxFrameBytes: Int = 8 * 1024 * 1024,
    /** Largest clientbound custom payload (the data after the channel name); vanilla 1 MiB. */
    val maxPayloadBytes: Int = 1024 * 1024,
    /** NBT estimate (vanilla's own heap accounting, the client quota is 2 MiB per tag) allowed for one network NBT tag. */
    val maxNbtBytes: Int = 1_900_000,
    val maxNbtDepth: Int = 64,
    /** Longest string inside an NBT tag of a component, in wire bytes (modified UTF-8). Vanilla's text limit is 32767 characters. */
    val maxStringBytes: Int = 32767,
    /** Largest component (chat/title/name...) in wire bytes. */
    val maxComponentBytes: Int = 262_144,
    /** Largest wire size of one item stack, and of one component of it (items above are sanitised: biggest components removed). */
    val maxItemBytes: Int = 1_048_576,
    val maxItemComponentBytes: Int = 524_288,
    /** Packets above this size are looked at for items (container content, equipment, entity data, merchant offers); smaller ones cannot hold a poisoned stack. */
    val itemScanBytes: Int = 8192,
    val maxBlockEntitiesNbt: Int = 1_900_000,
    /** An unfixable chunk: drop | disconnect. */
    val chunkPolicy: Policy = Policy.DROP,
    /** An unfixable packet the client needs to stay in step (login, registries, tags, configuration): disconnect | drop. */
    val criticalPolicy: Policy = Policy.DISCONNECT,
    /** Anything else that is too big (custom payloads, ...): drop | disconnect. */
    val otherPolicy: Policy = Policy.DROP,
    val sanitizeItems: Boolean = true,
    val sanitizeChunks: Boolean = true,
    val sanitizeText: Boolean = true,
    val disconnectMessage: String = "Disconnected by the FissionMC client safety layer: a backend sent data your client cannot handle",
    /** Log at most one line per player and reason this often. */
    val logIntervalSeconds: Int = 10,
    val placeholder: String = "[removed by FissionMC safety]",
) {
    companion object {
        private fun pos(key: String, v: Int, min: Int, max: Int = Int.MAX_VALUE): Int { if (v !in min..max) throw ConfigException("safety.$key: expected $min-${if (max == Int.MAX_VALUE) "" else max.toString()}, got $v"); return v }

        fun from(root: ConfigSection): SafetyConfig {
            val s = root.section("safety"); val d = SafetyConfig()
            fun int(k: String, def: Int, min: Int, max: Int) = pos(k, s.int(k, def), min, max)
            return SafetyConfig(
                s.bool("enabled", d.enabled),
                int("max-frame-bytes", d.maxFrameBytes, 1024, 8 * 1024 * 1024), int("max-payload-bytes", d.maxPayloadBytes, 1, 1024 * 1024),
                int("max-nbt-bytes", d.maxNbtBytes, 1024, 2 * 1024 * 1024), int("max-nbt-depth", d.maxNbtDepth, 2, 512),
                int("max-string-bytes", d.maxStringBytes, 16, 65535), int("max-component-bytes", d.maxComponentBytes, 256, 2 * 1024 * 1024),
                int("max-item-bytes", d.maxItemBytes, 512, 2 * 1024 * 1024), int("max-item-component-bytes", d.maxItemComponentBytes, 256, 2 * 1024 * 1024),
                int("item-scan-bytes", d.itemScanBytes, 0, 8 * 1024 * 1024), int("max-block-entity-nbt-bytes", d.maxBlockEntitiesNbt, 1024, 2 * 1024 * 1024),
                Policy.of("safety.chunk-policy", s.string("chunk-policy", "drop"), setOf(Policy.DROP, Policy.DISCONNECT)),
                Policy.of("safety.critical-policy", s.string("critical-policy", "disconnect"), setOf(Policy.DROP, Policy.DISCONNECT)),
                Policy.of("safety.other-policy", s.string("other-policy", "drop"), setOf(Policy.DROP, Policy.DISCONNECT)),
                s.bool("sanitize-items", true), s.bool("sanitize-chunks", true), s.bool("sanitize-text", true),
                s.string("disconnect-message", d.disconnectMessage).also { if (it.isBlank()) throw ConfigException("safety.disconnect-message: must not be empty") },
                int("log-interval-seconds", d.logIntervalSeconds, 0, 86400),
                s.string("placeholder", d.placeholder).also { if (it.length > 200) throw ConfigException("safety.placeholder: at most 200 characters") },
            )
        }
    }
}
