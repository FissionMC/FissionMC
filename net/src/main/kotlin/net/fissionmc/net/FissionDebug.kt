package net.fissionmc.net

/**
 * `-Dfission.debug` (or the environment variable FISSION_DEBUG=1) or `debug: true` in fission.yml turns on the chatty diagnostics: per-chunk leases, every replicated block,
 * periodic stats, Mixin's own log. Off by default. The flag and the environment variable force it on; the config value is live (see [fromConfig], called on every reload).
 */
object FissionDebug {
    private val forced: Boolean = (System.getProperty("fission.debug") ?: System.getenv("FISSION_DEBUG"))?.let { it.isEmpty() || it.equals("true", true) || it == "1" } ?: false
    @JvmField @Volatile var on: Boolean = forced

    /** Applies the config value `debug:`; the flag/env stay in force. */
    fun fromConfig(enabled: Boolean) { on = forced || enabled }
}
