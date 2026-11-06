package net.fissionmc.master.client

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Global chat settings (`chat.*` keys of the master's fission.properties).
 * Format codes: `&` colour/format codes and `&#rrggbb`; placeholders `{name} {displayname} {backend} {message}`.
 */
data class ChatSettings(
    /** `chat.default=local|global`: mode of a player who never used `/chat`. */
    val defaultGlobal: Boolean = false,
    /** `chat.global.format` */
    val format: String = "<{name}> {message}",
    /** `chat.global.prefix` / `chat.global.suffix` wrap the format (same codes). */
    val prefix: String = "",
    val suffix: String = "",
    /** `chat.global.allow-colors`: players may use `&` codes in their own text (default off: their text is always plain). */
    val allowColors: Boolean = false,
    /** `chat.global.max-length`: characters of player text that are broadcast. */
    val maxLength: Int = 256,
    /** `chat.global.timeout-ms`: how long the master waits for the sender's backend to run the chat events before dropping the message. */
    val timeoutMs: Long = 3000,
)

/** Everything the master reads from fission.properties besides the node and client listener keys. */
data class MasterSettings(
    /** `admin.players=Name,uuid,...` (case-insensitive): who may use `/fission send`. */
    val admins: Set<String> = emptySet(),
    val chat: ChatSettings = ChatSettings(),
) {
    fun isAdmin(name: String, uuid: java.util.UUID?): Boolean = name.lowercase() in admins || (uuid != null && uuid.toString().lowercase() in admins)

    companion object {
        fun parse(p: Properties): MasterSettings {
            fun get(k: String) = p.getProperty(k)?.trim()?.takeIf { it.isNotEmpty() }
            val d = ChatSettings()
            fun raw(k: String, def: String) = p.getProperty(k)?.takeIf { it.isNotBlank() || k.endsWith("prefix") || k.endsWith("suffix") } ?: def
            return MasterSettings(
                admins = (get("admin.players") ?: "").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
                chat = ChatSettings(
                    defaultGlobal = (get("chat.default") ?: "local").lowercase() == "global",
                    format = raw("chat.global.format", d.format), prefix = raw("chat.global.prefix", ""), suffix = raw("chat.global.suffix", ""),
                    allowColors = (get("chat.global.allow-colors") ?: "false").lowercase() == "true",
                    maxLength = get("chat.global.max-length")?.toInt() ?: d.maxLength,
                    timeoutMs = get("chat.global.timeout-ms")?.toLong() ?: d.timeoutMs,
                ),
            )
        }
        fun load(file: Path): MasterSettings = parse(Properties().also { p -> if (Files.isRegularFile(file)) Files.newBufferedReader(file).use(p::load) })
    }
}
