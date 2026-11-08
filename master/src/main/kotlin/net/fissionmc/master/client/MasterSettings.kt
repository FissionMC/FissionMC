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
 * The master's permission groups and who is in them (the `permissions:` section of fission.yml).
 *
 *  - `groups`: node -> operator level 0-4 (default: `fission.admin: 4`). Level 4 is the old "admin": full operator on every backend and the right to use the
 *    master's own `/fission` commands.
 *  - `players`: name or UUID -> nodes. A player gets EVERY listed node as a real Bukkit permission on every backend (level 0 groups too); the operator level is
 *    the highest level among its groups. Unknown group names are skipped with a WARN line.
 *
 * Players are keyed by UUID. A NAME entry only bootstraps: it is bound to the UUID of the first player who logs in with that name (if [bindNames]); the pair
 * is persisted in [file] (`permissions.txt`, lines `uuid name -`), a WARN line is logged, and from then on only the UUID counts (the nodes still come from
 * the name entry of fission.yml, so editing the yml changes them). `/fission admin add` writes `uuid name fission.admin` lines for runtime admins. In online
 * mode the UUID is authenticated, so binding once is safe; in offline mode (`client.online-mode=false`) the UUID derives from the name and anyone can claim a
 * name before its owner does (the master warns at start).
 *
 * A legacy `admins.txt` (lines `uuid name` of the old admin set) is read when `permissions.txt` does not exist yet and counts as `fission.admin`.
 */
class PermissionStore(
    private val file: Path? = null, val groups: Map<String, Int> = DEFAULT_GROUPS, entries: Map<String, List<String>> = emptyMap(),
    private val bindNames: Boolean = true, private val log: (String) -> Unit = {}, legacyFile: Path? = null,
) {
    private val staticNodes = HashMap<UUID, Set<String>>()                                 // uuid entries of fission.yml
    private val nameNodes = HashMap<String, Set<String>>()                                 // lowercase name entries of fission.yml
    private val pendingNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()  // lowercase names waiting for their first login
    private val bound = java.util.concurrent.ConcurrentHashMap<UUID, String>()             // persisted: uuid -> name bound by a first login
    private val extra = java.util.concurrent.ConcurrentHashMap<UUID, Set<String>>()        // persisted: nodes added at runtime (/fission admin add)

    init {
        for ((key, nodes) in entries) {
            val known = LinkedHashSet<String>()
            for (n in nodes.map { it.trim() }.filter { it.isNotEmpty() }) if (n in groups) known += n else log("WARN permissions.players.$key: unknown group '$n' (groups: ${groups.keys.joinToString(", ")}), ignored")
            val k = key.trim()
            parseUuid(k)?.let { staticNodes[it] = known } ?: run { nameNodes[k.lowercase()] = known; pendingNames.add(k.lowercase()) }
        }
        val use = file?.takeIf { Files.isRegularFile(it) } ?: legacyFile?.takeIf { Files.isRegularFile(it) }
        var migrated = false
        use?.let { f ->
            val legacy = f != file
            for (line in Files.readAllLines(f).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
                val parts = line.split(Regex("\\s+"), limit = 3)
                val u = parseUuid(parts[0]) ?: continue
                if (parts.size > 1) bound[u] = parts[1]
                val col = parts.getOrNull(2)
                val nodes = if (legacy) setOf(ADMIN) else if (col == null || col == "-") emptySet() else col.split(',').map { it.trim() }.filter { it in groups }.toSet()
                if (nodes.isNotEmpty()) extra[u] = nodes
                if (legacy) migrated = true
            }
        }
        // a name that is already bound to a uuid can never be bound again (an impostor with the same name stays out after a restart)
        bound.values.forEach { pendingNames.remove(it.lowercase()) }
        if (migrated) { log("WARN ${legacyFile?.fileName} migrated to ${file?.fileName}: its players are in $ADMIN now"); persist() }
    }

    private fun parseUuid(s: String): UUID? = try { UUID.fromString(s) } catch (_: IllegalArgumentException) { null }

    /** All group nodes of [uuid] (config uuid entry + config name entry it is bound to + runtime additions), in a stable order. */
    fun nodesOf(uuid: UUID?): Set<String> {
        if (uuid == null) return emptySet()
        val out = LinkedHashSet<String>()
        staticNodes[uuid]?.let { out += it }
        bound[uuid]?.let { n -> nameNodes[n.lowercase()]?.let { out += it } }
        extra[uuid]?.let { out += it }
        return out.filter { it in groups }.toSet()
    }
    /** The operator level the master grants [uuid]: the highest level among its groups (0 = none). */
    fun levelOf(uuid: UUID?): Int = nodesOf(uuid).maxOfOrNull { groups[it] ?: 0 } ?: 0
    fun has(uuid: UUID?, node: String): Boolean = node in nodesOf(uuid)
    /** Level 4: operator everywhere and the master's own commands. */
    fun isAdmin(uuid: UUID?): Boolean = levelOf(uuid) >= 4
    fun isStatic(uuid: UUID): Boolean = uuid in staticNodes
    val any: Boolean get() = staticNodes.isNotEmpty() || bound.isNotEmpty() || extra.isNotEmpty() || pendingNames.isNotEmpty()
    val pending: Set<String> get() = pendingNames.toSet()

    /** A player logged in: if its name is a pending bootstrap entry it is bound to this UUID. True when bound now. */
    fun bindOnLogin(name: String, uuid: UUID): Boolean {
        if (!bindNames || !pendingNames.remove(name.lowercase())) return false
        bound[uuid] = name; persist()
        log("WARN permissions: $name bound to uuid $uuid")
        return true
    }

    /** Runtime admin (`/fission admin add`): adds [ADMIN]. */
    fun add(uuid: UUID, name: String) { bound.putIfAbsent(uuid, name); extra[uuid] = (extra[uuid] ?: emptySet()) + ADMIN; pendingNames.remove(name.lowercase()); persist() }

    /** Removes the runtime/bound entries of [uuid]. False if there were none (static uuid entries of fission.yml are edited in the file). */
    fun remove(uuid: UUID): Boolean { val r = (extra.remove(uuid) != null) or (bound.remove(uuid) != null); if (r) persist(); return r }
    fun uuidByName(name: String): UUID? = bound.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key

    private fun persist() {
        file ?: return
        val lines = bound.entries.sortedBy { it.value.lowercase() }.map { "${it.key} ${it.value} ${extra[it.key]?.joinToString(",") ?: "-"}" } +
            extra.entries.filter { !bound.containsKey(it.key) }.map { "${it.key} - ${it.value.joinToString(",")}" }
        try { Files.write(file, listOf("# master permissions: uuid name nodes (written by the master; '-' = the nodes come from fission.yml)") + lines + pendingNames.sorted().map { "# waiting for first login: $it" }) }
        catch (e: Exception) { log("WARN cannot write $file: $e") }
    }

    companion object {
        const val ADMIN = "fission.admin"
        val DEFAULT_GROUPS: Map<String, Int> = mapOf(ADMIN to 4)

        /**
         * Reads `permissions:` (and the legacy `admin:` section, which counts as [ADMIN] with a one-time warning) of [root].
         * `players` values are a comma separated text or a yaml list.
         */
        fun from(root: ConfigSection, file: Path? = null, legacyFile: Path? = null, log: (String) -> Unit = {}): PermissionStore {
            val perm = root.section("permissions"); val g = perm.section("groups"); val p = perm.section("players")
            val groups = LinkedHashMap<String, Int>()
            for (k in g.keys()) groups[k] = g.int(k, 0).also { if (it !in 0..4) throw ConfigException("permissions.groups.$k: expected an operator level 0-4, got $it") }
            val entries = LinkedHashMap<String, List<String>>()
            for (k in p.keys()) entries[k] = p.stringList(k).flatMap { it.split(',') }
            var bind = perm.bool("bind-names", true)
            val legacy = root.section("admin")   // always read, so the old section never counts as an unknown key
            val old = legacy.stringList("players").flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
            val oldBind = legacy.bool("bind-names", true)
            if (old.isNotEmpty()) {
                if (!perm.has("bind-names")) bind = oldBind
                log("WARN fission.yml: the admin: section is deprecated; its ${old.size} player(s) are treated as $ADMIN. Move them to permissions: players: { <name>: $ADMIN } and remove admin:")
                if (ADMIN !in groups) groups[ADMIN] = 4
                for (n in old) entries[n] = (entries[n] ?: emptyList()) + ADMIN
            }
            if (groups.isEmpty()) groups += DEFAULT_GROUPS
            return PermissionStore(file, groups, entries, bind, log, legacyFile)
        }
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
    val permissions: PermissionStore = PermissionStore(),
    val messages: AnnouncementSettings = AnnouncementSettings(),
    /** `travel.pet-radius` */
    val petRadius: Int = 24,
    /** `switch.mode`: `seamless` (default: a client stays in its level when the new backend's level is the same, no Login/Respawn, no loading screen) or `login` (always Login + Respawn). */
    val switchMode: String = "seamless",
    /** `switch.fresh-position` (default true): hand the new backend the client's real position at the end of a switch instead of the one saved when the switch started. */
    val switchFreshPosition: Boolean = true,
    /** `regions.margin` blocks of hysteresis at a region border (default 8). */
    val regionMargin: Int = 8,
    /** `cross-commands:` (tp to a player on another backend), see [CrossCommands]. */
    val cross: CrossCommandSettings = CrossCommandSettings(),
    /** `ghosts:` (players of other backends shown as entities). */
    val ghosts: GhostSettings = GhostSettings(),
) {
    val announcements: Announcements by lazy { Announcements(messages) }
    fun isAdmin(uuid: UUID?): Boolean = permissions.isAdmin(uuid)

    companion object {
        /**
         * Reads the master's sections of [root]: `chat`, `messages`, `permissions` (and the legacy `admin`), `travel` and `regions.margin` (every key documented in fission.default.yml). The caller runs
         * `root.finish()` once all readers are done, which reports unknown keys with their path.
         */
        fun from(root: ConfigSection, permissionsFile: Path? = null, legacyAdminsFile: Path? = null, log: (String) -> Unit = {}): MasterSettings {
            val d = ChatSettings()
            val c = root.section("chat"); val fmt = c.section("format"); val g = c.section("global"); val l = c.section("local")
            val default = c.string("default", "local").lowercase()
            if (default != "local" && default != "global") throw ConfigException("chat.default: expected local or global, got '$default'")
            val syntax = fmt.string("syntax", d.syntax).lowercase()
            if (syntax != "minimessage" && syntax != "legacy") throw ConfigException("chat.format.syntax: expected minimessage or legacy, got '$syntax'")
            val m = root.section("messages"); val join = m.section("join"); val leave = m.section("leave"); val sw = m.section("switch"); val loc = m.section("local")
            return MasterSettings(
                chat = ChatSettings(
                    defaultGlobal = default == "global", syntax = syntax,
                    format = g.string("format", d.format), localFormat = l.string("format", d.localFormat), prefix = g.string("prefix", ""), suffix = g.string("suffix", ""),
                    allowColors = g.bool("allow-colors", false), maxLength = g.int("max-length", d.maxLength), timeoutMs = g.long("timeout-ms", d.timeoutMs),
                ),
                switchMode = root.section("switch").string("mode", "seamless").lowercase().also { if (it != "seamless" && it != "login") throw ConfigException("switch.mode: expected seamless or login, got '$it'") },
                switchFreshPosition = root.section("switch").bool("fresh-position", true),
                regionMargin = root.section("regions").int("margin", 8).also { if (it !in 0..128) throw ConfigException("regions.margin: expected 0-128, got $it") },
                cross = CrossCommandSettings.from(root), ghosts = GhostSettings.from(root),
                petRadius = root.section("travel").int("pet-radius", 24).also { if (it !in 0..128) throw ConfigException("travel.pet-radius: expected 0-128, got $it") },
                messages = AnnouncementSettings(
                    joinGlobal = join.bool("global", true), leaveGlobal = leave.bool("global", true), joinFormat = join.string("format", ""), leaveFormat = leave.string("format", ""),
                    switchEnabled = sw.bool("enabled", false), switchFormat = sw.string("format", "{name} moved to {backend}"), localEnabled = loc.bool("enabled", false),
                ),
                permissions = PermissionStore.from(root, permissionsFile, legacyAdminsFile, log),
            )
        }

        fun load(file: Path, log: (String) -> Unit = {}): MasterSettings = from(FissionConfig.load(file), file.toAbsolutePath().resolveSibling("permissions.txt"), file.toAbsolutePath().resolveSibling("admins.txt"), log)
    }
}
