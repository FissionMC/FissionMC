package net.fissionmc.master.client

import net.fissionmc.net.ConfigException
import net.fissionmc.net.ConfigSection
import net.fissionmc.net.FissionConfig
import java.nio.file.Path

/** Client-facing listener settings, the `client:` section of the master's `fission.yml`. */
data class ClientConfig(
    val host: String = "0.0.0.0",
    val port: Int = 25565,
    val onlineMode: Boolean = true,
    val motd: String = "A FissionMC network",
    val maxPlayers: Int = 100,
    val compressionThreshold: Int = 256,
    val enabled: Boolean = true,
    /** Was `client.listen` written in the file? (a combined backend only hosts a client listener when it was) */
    val listenGiven: Boolean = false,
    /** `client.connection-throttle`: new connections one address may open per 10 seconds (0 = no limit; loopback is never limited). */
    val throttle: Int = 50,
    /** `client.timeout-seconds`: a connection that sends nothing for this long, or has not logged in after this long, is closed. */
    val timeoutSeconds: Int = 30,
) {
    companion object {
        fun load(file: Path): ClientConfig = from(FissionConfig.load(file))

        fun from(root: ConfigSection): ClientConfig {
            val c = root.section("client")
            val d = ClientConfig()
            val listen = c.optString("listen")?.trim()?.takeIf { it.isNotEmpty() }
            val (h, po) = if (listen == null) d.host to d.port else {
                val i = listen.lastIndexOf(':')
                if (i <= 0) throw ConfigException("client.listen: expected host:port, got '$listen'")
                listen.substring(0, i) to (listen.substring(i + 1).toIntOrNull() ?: throw ConfigException("client.listen: the port of '$listen' is not a number"))
            }
            return ClientConfig(
                h, po, c.bool("online-mode", true), c.string("motd", d.motd), c.int("max-players", d.maxPlayers),
                c.int("compression-threshold", d.compressionThreshold), c.bool("enabled", true), listen != null,
                c.int("connection-throttle", d.throttle).also { if (it < 0) throw ConfigException("client.connection-throttle: expected 0 or more, got $it") },
                c.int("timeout-seconds", d.timeoutSeconds).also { if (it < 5) throw ConfigException("client.timeout-seconds: expected 5 or more, got $it") },
            )
        }
    }
}
