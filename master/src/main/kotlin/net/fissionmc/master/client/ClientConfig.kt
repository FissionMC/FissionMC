package net.fissionmc.master.client

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Client-facing listener settings, read from the master's fission.properties (`client.*` keys). */
data class ClientConfig(
    val host: String = "0.0.0.0",
    val port: Int = 25565,
    val onlineMode: Boolean = true,
    val motd: String = "A FissionMC network",
    val maxPlayers: Int = 100,
    val compressionThreshold: Int = 256,
    val enabled: Boolean = true,
) {
    companion object {
        fun load(file: Path): ClientConfig {
            val p = Properties()
            if (Files.isRegularFile(file)) Files.newBufferedReader(file).use(p::load)
            fun get(k: String) = p.getProperty(k)?.trim()?.takeIf { it.isNotEmpty() }
            val d = ClientConfig()
            val listen = get("client.listen")
            val (h, po) = if (listen == null) d.host to d.port else {
                val i = listen.lastIndexOf(':'); require(i > 0) { "client.listen must be host:port" }
                listen.substring(0, i) to listen.substring(i + 1).toInt()
            }
            return ClientConfig(
                h, po, (get("client.online-mode") ?: "true").lowercase() != "false",
                get("client.motd") ?: d.motd, get("client.max-players")?.toInt() ?: d.maxPlayers,
                get("client.compression-threshold")?.toInt() ?: d.compressionThreshold,
                (get("client.enabled") ?: "true").lowercase() != "false",
            )
        }
    }
}
