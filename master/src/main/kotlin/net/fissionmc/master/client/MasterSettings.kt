package net.fissionmc.master.client

import java.nio.file.Files
import net.fissionmc.net.ConfigException
import net.fissionmc.net.ConfigSection
import net.fissionmc.net.FissionConfig
import java.nio.file.Path
import java.util.UUID

/**
 * Chat settings (the `chat:` section of the master's fission.yml).
 * Syntax `chat.format.syntax=minimessage|legacy` (see [net.fissionmc.master.chat.ChatTemplate]); placeholders `{name} {displayname} {uuid} {serverid} {backend} {message}`.
 */
data class ChatSettings(
    /** `chat.default=local|global`: mode of a player who never used `/chat`. */
    val defaultGlobal: Boolean = false,
    /** `chat.format.syntax=minimessage|legacy` (default minimessage; `&` codes also work inside minimessage templates). */
    val syntax: String = "minimessage",
    /** `chat.global.format` (empty = the vanilla looking default `<name> message`). */
    val format: String = "",
    /** `chat.local.format`: template of LOCAL chat lines, pushed to the backends and rendered there (empty = vanilla rendering on the backend). */
    val localFormat: String = "",
    /** `chat.global.prefix` / `chat.global.suffix` wrap the format (same codes). */
    val prefix: String = "",
    val suffix: String = "",
    /** `chat.global.allow-colors`: players may use `&` codes in their own text (default off: their text is always plain). */
    val allowColors: Boolean = false,
    /** `chat.global.max-length`: characters of player text that are broadcast. */
    val maxLength: Int = 256,
    /** `chat.global.timeout-ms`: how long the master waits for the sender's backend to run the chat events before dropping the message. */
    val timeoutMs: Long = 3000,
) {
    /** The global template: [format], or the default for the syntax (a literal `<` must be escaped in MiniMessage). */
    fun globalTemplate(): String = format.ifEmpty { if (syntax.equals("legacy", ignoreCase = true)) "<{name}> {message}" else "\\<{name}> {message}" }
}

/**
 * The master's admins, keyed by UUID (admin == operator level 4 on every backend, and who may use the master's own commands).
 * Sources: `admin: players: [<uuid>, ...]` in fission.yml (static, removable only by editing the file) and `admins.txt` next to it (lines
 * `uuid name`, written by `/fission admin add` and by name binding). NAMES are only a bootstrap: a name entry (in either file) is bound to the UUID of the
 * first player who logs in with that name (if [bindNames]); the pair is persisted in admins.txt, a WARN line is logged, and from then on only the UUID
 * counts. In online mode the UUID is authenticated, so binding once is safe; in offline mode (`client.online-mode=false`) the UUID derives from
 * the name and anyone can claim an admin's name before the admin does (the master warns at start).
 */
class AdminStore(
    private val file: Path? = null, entries: List<String> = emptyList(), private val bindNames: Boolean = true, private val log: (String) -> Unit = {},
) {
    private val staticUuids = HashSet<UUID>()
    private val uuids = java.util.concurrent.ConcurrentHashMap<UUID, String>()          // persisted: uuid -> name (comment)
    private val pendingNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>() // lowercase names waiting for their first login

    init {
        for (e in entries.map { it.trim() }.filter { it.isNotEmpty() }) parseUuid(e)?.let { staticUuids += it } ?: pendingNames.add(e.lowercase())
        file?.takeIf { Files.isRegularFile(it) }?.let { f ->
            for (line in Files.readAllLines(f).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
                val parts = line.split(Regex("\\s+"), limit = 2)
                val u = parseUuid(parts[0])
                if (u != null) uuids[u] = parts.getOrElse(1) { "" } else pendingNames.add(parts[0].lowercase())
            }
        }
        // a name that is already bound to a uuid can never be bound again (an impostor with the same name stays out after a restart)
        uuids.values.forEach { pendingNames.remove(it.lowercase()) }
    }

    private fun parseUuid(s: String): UUID? = try { UUID.fromString(s) } catch (_: IllegalArgumentException) { null }

    fun isAdmin(uuid: UUID?): Boolean = uuid != null && (uuid in staticUuids || uuids.containsKey(uuid))
    fun isStatic(uuid: UUID): Boolean = uuid in staticUuids
    val any: Boolean get() = staticUuids.isNotEmpty() || uuids.isNotEmpty() || pendingNames.isNotEmpty()
    val pending: Set<String> get() = pendingNames.toSet()

    /** A player logged in: if its name is a pending bootstrap entry it is bound to this UUID. True when bound now. */
    fun bindOnLogin(name: String, uuid: UUID): Boolean {
        if (!bindNames || !pendingNames.remove(name.lowercase())) return false
        uuids[uuid] = name; persist()
        log("WARN admin $name bound to uuid $uuid")
        return true
    }

    fun add(uuid: UUID, name: String) { uuids[uuid] = name; pendingNames.remove(name.lowercase()); persist() }

    /** Removes a runtime admin. False if it is not one (or is a static entry of fission.yml). */
    fun remove(uuid: UUID): Boolean { val r = uuids.remove(uuid) != null; if (r) persist(); return r }
    fun uuidByName(name: String): UUID? = uuids.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key

    private fun persist() {
        file ?: return
        try { Files.write(file, listOf("# master admins: uuid name (written by the master)") + uuids.entries.sortedBy { it.value.lowercase() }.map { "${it.key} ${it.value}".trim() } + pendingNames.sorted()) }
        catch (e: Exception) { log("WARN cannot write $file: $e") }
    }
}

/**
 * `messages.*` keys: who announces joins and leaves. Default: the master, network wide, once per login (not per backend switch), with vanilla's own
 * translatable lines. `messages.local.enabled=true` hands the announcements back to each backend (vanilla per-backend messages) and the master stays silent.
 */
data class AnnouncementSettings(
    val joinGlobal: Boolean = true, val leaveGlobal: Boolean = true,
    /** Empty = vanilla's `multiplayer.player.joined` / `.left` (yellow translatable). Otherwise `&` codes with {name} {displayname} {backend}. */
    val joinFormat: String = "", val leaveFormat: String = "",
    val switchEnabled: Boolean = false, val switchFormat: String = "{name} moved to {backend}",
    val localEnabled: Boolean = false,
)

/** Chooses the announcement lines (pure, unit tested). Null = say nothing. */
class Announcements(private val s: AnnouncementSettings) {
    private val legacy = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder().character('&').hexColors().build()

    private fun render(template: String, name: String, backend: String?): net.kyori.adventure.text.Component {
        var c: net.kyori.adventure.text.Component = legacy.deserialize(template)
        fun rep(token: String, value: String) { c = c.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder().matchLiteral(token).replacement(net.kyori.adventure.text.Component.text(value)).build()) }
        rep("{name}", name); rep("{displayname}", name); rep("{backend}", backend ?: "")
        return c
    }

    private fun vanilla(key: String, name: String) = net.kyori.adventure.text.Component.translatable(key, net.kyori.adventure.text.format.NamedTextColor.YELLOW, net.kyori.adventure.text.Component.text(name))

    fun join(name: String, backend: String?): net.kyori.adventure.text.Component? =
        if (s.localEnabled || !s.joinGlobal) null else if (s.joinFormat.isEmpty()) vanilla("multiplayer.player.joined", name) else render(s.joinFormat, name, backend)

    fun leave(name: String, backend: String?): net.kyori.adventure.text.Component? =
        if (s.localEnabled || !s.leaveGlobal) null else if (s.leaveFormat.isEmpty()) vanilla("multiplayer.player.left", name) else render(s.leaveFormat, name, backend)

    /** A move between backends: only when asked for, and never together with local announcements. */
    fun switched(name: String, toBackend: String): net.kyori.adventure.text.Component? =
        if (s.localEnabled || !s.switchEnabled) null else render(s.switchFormat, name, toBackend)

    /** The backends announce themselves (vanilla per backend). */
    val local: Boolean get() = s.localEnabled
}

/** Everything the master reads from fission.yml besides the node and client listener keys. */
data class MasterSettings(
    val chat: ChatSettings = ChatSettings(),
    val adminStore: AdminStore = AdminStore(),
    val messages: AnnouncementSettings = AnnouncementSettings(),
    /** `travel.pet-radius` */
    val petRadius: Int = 24,
    /** `regions.margin` blocks of hysteresis at a region border (default 8). */
    val regionMargin: Int = 8,
) {
    val announcements: Announcements by lazy { Announcements(messages) }
    fun isAdmin(uuid: UUID?): Boolean = adminStore.isAdmin(uuid)

    companion object {
        /**
         * Reads the master's sections of [root]: `chat`, `messages`, `admin`, `travel` and `regions.margin` (every key documented in fission.default.yml). The caller runs
         * `root.finish()` once all readers are done, which reports unknown keys with their path.
         */
        fun from(root: ConfigSection, adminsFile: Path? = null, log: (String) -> Unit = {}): MasterSettings {
            val d = ChatSettings()
            val c = root.section("chat"); val fmt = c.section("format"); val g = c.section("global"); val l = c.section("local")
            val default = c.string("default", "local").lowercase()
            if (default != "local" && default != "global") throw ConfigException("chat.default: expected local or global, got '$default'")
            val syntax = fmt.string("syntax", d.syntax).lowercase()
            if (syntax != "minimessage" && syntax != "legacy") throw ConfigException("chat.format.syntax: expected minimessage or legacy, got '$syntax'")
            val m = root.section("messages"); val join = m.section("join"); val leave = m.section("leave"); val sw = m.section("switch"); val loc = m.section("local")
            val admin = root.section("admin")
            return MasterSettings(
                chat = ChatSettings(
                    defaultGlobal = default == "global", syntax = syntax,
                    format = g.string("format", d.format), localFormat = l.string("format", d.localFormat), prefix = g.string("prefix", ""), suffix = g.string("suffix", ""),
                    allowColors = g.bool("allow-colors", false), maxLength = g.int("max-length", d.maxLength), timeoutMs = g.long("timeout-ms", d.timeoutMs),
                ),
                regionMargin = root.section("regions").int("margin", 8).also { if (it !in 0..128) throw ConfigException("regions.margin: expected 0-128, got $it") },
                petRadius = root.section("travel").int("pet-radius", 24).also { if (it !in 0..128) throw ConfigException("travel.pet-radius: expected 0-128, got $it") },
                messages = AnnouncementSettings(
                    joinGlobal = join.bool("global", true), leaveGlobal = leave.bool("global", true), joinFormat = join.string("format", ""), leaveFormat = leave.string("format", ""),
                    switchEnabled = sw.bool("enabled", false), switchFormat = sw.string("format", "{name} moved to {backend}"), localEnabled = loc.bool("enabled", false),
                ),
                adminStore = AdminStore(adminsFile, admin.stringList("players").flatMap { it.split(',') }, admin.bool("bind-names", true), log),
            )
        }

        fun load(file: Path, log: (String) -> Unit = {}): MasterSettings = from(FissionConfig.load(file), file.toAbsolutePath().resolveSibling("admins.txt"), log)
    }
}
