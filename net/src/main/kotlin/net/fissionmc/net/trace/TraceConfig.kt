package net.fissionmc.net.trace

import net.fissionmc.net.ConfigException
import net.fissionmc.net.ConfigSection

/** The `trace:` section of `fission.yml` (master and backend): the flight recorder, see `.dev/docs/trace.md`. */
data class TraceConfig(val enabled: Boolean = false, val ringSeconds: Int = 300, val maxFileMb: Int = 64) {
    companion object {
        fun from(root: ConfigSection): TraceConfig {
            val t = root.section("trace")
            val c = TraceConfig(t.bool("enabled", false), t.int("ring-seconds", 300), t.int("max-file-mb", 64))
            if (c.ringSeconds !in 1..86_400) throw ConfigException("trace.ring-seconds: expected 1-86400, got ${c.ringSeconds}")
            if (c.maxFileMb !in 1..4096) throw ConfigException("trace.max-file-mb: expected 1-4096, got ${c.maxFileMb}")
            return c
        }
    }
}
