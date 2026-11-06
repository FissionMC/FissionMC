package net.fissionmc.master.client

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/** Slash commands the master handles itself (they never reach a backend): `/chat global|local|toggle`, `/fission send <player> <backend>`. */
object MasterCommands {
    /** True when [command] (without the slash) was handled here. */
    fun handle(session: ClientSession, server: ClientServer, command: String): Boolean {
        val parts = command.trim().split(Regex("\\s+"))
        return when (parts.firstOrNull()?.lowercase()) {
            "chat" -> { chat(session, parts.getOrNull(1)?.lowercase()); true }
            "fission" -> when (parts.getOrNull(1)?.lowercase()) {
                "send" -> { send(session, server, parts.drop(2)); true }
                "admin" -> { admin(session, server, parts.drop(2)); true }
                "regions" -> { regions(session, server); true }
                else -> false
            }
            else -> false
        }
    }

    private fun chat(s: ClientSession, arg: String?) {
        val mode = when (arg) { "global" -> true; "local" -> false; "toggle" -> !s.chatGlobal; else -> { s.tell(Component.text("Usage: /chat global|local|toggle", NamedTextColor.RED)); return } }
        s.chatGlobal = mode
        s.tell(Component.text(if (mode) "Chat mode: global (everyone on the network reads you)" else "Chat mode: local (only players on your backend)", NamedTextColor.GRAY))
    }

    /**
     * `/fission admin add|remove <player>`: edits the admin set, which is keyed by UUID (admins.txt next to fission.properties). Admin = operator level 4
     * on every backend. `add` needs the player online (its UUID is resolved from the name); `remove` takes an online name, a name recorded in admins.txt or a uuid.
     */
    private fun admin(s: ClientSession, server: ClientServer, args: List<String>) {
        if (!s.isAdmin) { s.tell(Component.text("You are not allowed to do that.", NamedTextColor.RED)); return }
        val action = args.getOrNull(0)?.lowercase(); val who = args.getOrNull(1)
        if ((action != "add" && action != "remove") || who == null || args.size != 2) { s.tell(Component.text("Usage: /fission admin add|remove <player>", NamedTextColor.RED)); return }
        val store = server.settings.adminStore
        val online = server.findByName(who)
        val uuid = online?.uuid ?: try { java.util.UUID.fromString(who) } catch (_: IllegalArgumentException) { null } ?: store.uuidByName(who)
        if (uuid == null) { s.tell(Component.text("Unknown player: $who (it has to be online, or give a uuid)", NamedTextColor.RED)); return }
        if (action == "add") {
            store.add(uuid, online?.playerName ?: who)
            s.tell(Component.text("${online?.playerName ?: who} ($uuid) is an admin now (operator level 4 on every backend).", NamedTextColor.GRAY))
        } else if (store.remove(uuid)) {
            s.tell(Component.text("${online?.playerName ?: who} is no admin any more.", NamedTextColor.GRAY))
        } else {
            s.tell(Component.text(if (store.isStatic(uuid)) "$who is listed in admin.players of fission.properties: remove it there and restart the master." else "$who is not an admin.", NamedTextColor.RED)); return
        }
        server.log("[master] ${s.playerName}: admin $action $who ($uuid)")
        online?.pushPermission()
    }

    /** `/fission regions`: the owner at the player's position and the map. */
    private fun regions(s: ClientSession, server: ClientServer) {
        if (!s.isAdmin) { s.tell(Component.text("You are not allowed to do that.", NamedTextColor.RED)); return }
        val map = server.relay?.regions
        if (map == null || !map.enabled) { s.tell(Component.text("Regions are off: every backend owns everything (set regions.* in fission.properties).", NamedTextColor.GRAY)); return }
        val owner = map.owner(s.lastDimension, s.lastX, s.lastZ)
        s.tell(Component.text("You are at ${s.lastDimension} ${s.lastX}, ${s.lastZ}: owner ${owner ?: "nobody"}, on ${s.currentNodeId}.", NamedTextColor.GRAY))
        map.describe().forEach { s.tell(Component.text("  $it", NamedTextColor.DARK_GRAY)) }
    }

    private fun send(s: ClientSession, server: ClientServer, args: List<String>) {
        if (!s.isAdmin) { s.tell(Component.text("You are not allowed to do that.", NamedTextColor.RED)); return }
        if (args.size != 2) { s.tell(Component.text("Usage: /fission send <player> <backend>", NamedTextColor.RED)); return }
        val target = server.findByName(args[0]) ?: run { s.tell(Component.text("No such player: ${args[0]}", NamedTextColor.RED)); return }
        server.log("[master] ${s.playerName} sends ${target.playerName} to ${args[1]}")
        target.startSwitch(args[1]) { why -> s.tell(Component.text("Cannot send ${target.playerName} to ${args[1]}: $why", NamedTextColor.RED)) }
        s.tell(Component.text("Sending ${target.playerName} to ${args[1]}...", NamedTextColor.GRAY))
        val map = server.relay?.regions
        if (map != null && map.enabled) { val o = map.owner(target.lastDimension, target.lastX, target.lastZ); if (o != null && o != args[1]) s.tell(Component.text("Note: ${target.playerName} stands in ${o}'s region, regions will move it back.", NamedTextColor.YELLOW)) }
    }

    /**
     * Answers a ServerboundCommandSuggestion for `/fission send <player> <backend>` (nodes with `minecraft:ask_server` suggestions): online player
     * names, then backend ids. Layout: request = varint transaction id, string command (with the leading slash); response (ClientboundCommandSuggestions) =
     * varint id, varint start, varint length, varint count, then per entry string text + bool hasTooltip. Null = not ours, the backend answers.
     */
    fun suggest(frame: ByteArray, server: ClientServer): ByteArray? {
        val b = io.netty.buffer.Unpooled.wrappedBuffer(frame)
        VarInts.tryRead(b) ?: return null
        val tx = VarInts.tryRead(b) ?: return null
        val n = VarInts.tryRead(b) ?: return null
        if (n < 0 || n > b.readableBytes()) return null
        val text = ByteArray(n).also { b.readBytes(it) }.toString(Charsets.UTF_8)
        val body = text.removePrefix("/"); val lead = text.length - body.length
        val prefix = listOf("fission send ", "fission admin add ", "fission admin remove ").firstOrNull { body.lowercase().startsWith(it) } ?: return null
        val rest = body.substring(prefix.length)
        val parts = rest.split(' ')
        val token = parts.last()
        val candidates = when (parts.size) {
            1 -> server.allSessions().map { it.playerName }
            2 -> if (prefix == "fission send ") server.relay?.liveNodeIds() ?: emptyList() else emptyList()
            else -> emptyList()
        }.filter { it.startsWith(token, ignoreCase = true) }.sorted()
        val out = io.netty.buffer.Unpooled.buffer()
        VarInts.write(out, PlayIds.commandSuggestions); VarInts.write(out, tx)
        VarInts.write(out, lead + prefix.length + (rest.length - token.length)); VarInts.write(out, token.length)
        VarInts.write(out, candidates.size)
        candidates.forEach { c -> val cb = c.toByteArray(); VarInts.write(out, cb.size); out.writeBytes(cb); out.writeBoolean(false) }
        return ByteArray(out.readableBytes()).also { out.readBytes(it) }
    }
}
