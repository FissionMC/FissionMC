package net.fissionmc.net

/** `-Dfission.debug` (or the environment variable FISSION_DEBUG=1) turns on the chatty diagnostics: per-chunk leases, every replicated block, periodic stats, Mixin's own log. Off by default. */
object FissionDebug {
    @JvmField val on: Boolean = (System.getProperty("fission.debug") ?: System.getenv("FISSION_DEBUG"))?.let { it.isEmpty() || it.equals("true", true) || it == "1" } ?: false
}
