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
    /** The real player cap; [UNLIMITED] (written `unlimited` or -1) lets everybody join. */
    val maxPlayers: Int = 100,
    /** The cap shown in the server list (-1 = the real cap; with an unlimited cap one more than are online). */
    val fakeMaxPlayers: Int = -1,
    /** The online count shown in the server list (-1 = the real count). */
    val fakeOnlinePlayers: Int = -1,
    /** The shown online count is at least this (ignored when [fakeOnlinePlayers] is set). */
    val fakeOnlineMinimum: Int = 0,
    val compressionThreshold: Int = 256,
    val enabled: Boolean = true,
    /** Was `client.listen` written in the file? (a combined backend only hosts a client listener when it was) */
    val listenGiven: Boolean = false,
    /** `client.connection-throttle`: new connections one address may open per 10 seconds (0 = no limit; loopback is never limited). */
    val throttle: Int = 50,
    /** `client.timeout-seconds`: a connection that sends nothing for this long, or has not logged in after this long, is closed. */
    val timeoutSeconds: Int = 30,
) {
    /** @return the online count shown in the server list for [real] players */
    fun shownOnline(real: Int): Int = if (fakeOnlinePlayers >= 0) fakeOnlinePlayers else Math.max(real, fakeOnlineMinimum)

    /** @return the cap shown in the server list when [real] players are online (at least the shown online count, so the list never says 5/3) */
    fun shownMax(real: Int): Int {
        val base = if (fakeMaxPlayers >= 0) fakeMaxPlayers else if (maxPlayers == UNLIMITED) real + 1 else maxPlayers
        return Math.max(base, 0)
    }

    /** @return true when a player may join with [real] players online */
    fun hasRoom(real: Int): Boolean = maxPlayers == UNLIMITED || real < maxPlayers

    companion object {
        const val UNLIMITED = -1

        private fun cap(c: ConfigSection, d: Int): Int {
            val t = c.string("max-players", d.toString()).trim().lowercase()
            if (t == "unlimited" || t == "infinite") return UNLIMITED
            val n = t.toIntOrNull() ?: throw ConfigException("client.max-players: expected a whole number or 'unlimited', got '$t'")
            if (n < -1) throw ConfigException("client.max-players: expected 0 or more, -1 or 'unlimited', got $n")
            return n
        }

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
                h, po, c.bool("online-mode", true), c.string("motd", d.motd), cap(c, d.maxPlayers),
                c.int("fake-max-players", d.fakeMaxPlayers).also { if (it < -1) throw ConfigException("client.fake-max-players: expected 0 or more or -1, got $it") },
                c.int("fake-online-players", d.fakeOnlinePlayers).also { if (it < -1) throw ConfigException("client.fake-online-players: expected 0 or more or -1, got $it") },
                c.int("fake-online-minimum", d.fakeOnlineMinimum).also { if (it < 0) throw ConfigException("client.fake-online-minimum: expected 0 or more, got $it") },
                c.int("compression-threshold", d.compressionThreshold), c.bool("enabled", true), listen != null,
                c.int("connection-throttle", d.throttle).also { if (it < 0) throw ConfigException("client.connection-throttle: expected 0 or more, got $it") },
                c.int("timeout-seconds", d.timeoutSeconds).also { if (it < 5) throw ConfigException("client.timeout-seconds: expected 5 or more, got $it") },
            )
        }
    }
}
