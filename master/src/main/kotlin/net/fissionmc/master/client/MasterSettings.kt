package net.fissionmc.master.client

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

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

/**
 * The master's admins, keyed by UUID (admin == operator level 4 on every backend, and who may use the master's own commands).
 * Sources: `admin.players=<uuid>,...` in fission.properties (static, removable only by editing the file) and `admins.txt` next to it (lines
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

    /** Removes a runtime admin. False if it is not one (or is a static entry of fission.properties). */
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

/** Everything the master reads from fission.properties besides the node and client listener keys. */
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
        fun parse(p: Properties, adminsFile: Path? = null, log: (String) -> Unit = {}): MasterSettings {
            fun get(k: String) = p.getProperty(k)?.trim()?.takeIf { it.isNotEmpty() }
            val d = ChatSettings()
            fun raw(k: String, def: String) = p.getProperty(k)?.takeIf { it.isNotBlank() || k.endsWith("prefix") || k.endsWith("suffix") } ?: def
            return MasterSettings(
                chat = ChatSettings(
                    defaultGlobal = (get("chat.default") ?: "local").lowercase() == "global",
                    format = raw("chat.global.format", d.format), prefix = raw("chat.global.prefix", ""), suffix = raw("chat.global.suffix", ""),
                    allowColors = (get("chat.global.allow-colors") ?: "false").lowercase() == "true",
                    maxLength = get("chat.global.max-length")?.toInt() ?: d.maxLength,
                    timeoutMs = get("chat.global.timeout-ms")?.toLong() ?: d.timeoutMs,
                ),
                regionMargin = get("regions.margin")?.toIntOrNull()?.coerceIn(0, 128) ?: 8,
                petRadius = get("travel.pet-radius")?.toIntOrNull()?.coerceIn(0, 128) ?: 24,
                messages = AnnouncementSettings(
                    joinGlobal = (get("messages.join.global") ?: "true").lowercase() != "false", leaveGlobal = (get("messages.leave.global") ?: "true").lowercase() != "false",
                    joinFormat = p.getProperty("messages.join.format")?.trim() ?: "", leaveFormat = p.getProperty("messages.leave.format")?.trim() ?: "",
                    switchEnabled = (get("messages.switch.enabled") ?: "false").lowercase() == "true",
                    switchFormat = p.getProperty("messages.switch.format")?.trim()?.takeIf { it.isNotEmpty() } ?: "{name} moved to {backend}",
                    localEnabled = (get("messages.local.enabled") ?: "false").lowercase() == "true",
                ),
                adminStore = AdminStore(adminsFile, (get("admin.players") ?: "").split(','), (get("admin.bind-names") ?: "true").lowercase() != "false", log),
            )
        }
        fun load(file: Path, log: (String) -> Unit = {}): MasterSettings =
            parse(Properties().also { p -> if (Files.isRegularFile(file)) Files.newBufferedReader(file).use(p::load) }, file.toAbsolutePath().resolveSibling("admins.txt"), log)
    }
}
