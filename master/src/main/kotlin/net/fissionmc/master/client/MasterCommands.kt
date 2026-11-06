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
            "fission" -> if (parts.getOrNull(1)?.lowercase() == "send") { send(session, server, parts.drop(2)); true } else false
            else -> false
        }
    }

    private fun chat(s: ClientSession, arg: String?) {
        val mode = when (arg) { "global" -> true; "local" -> false; "toggle" -> !s.chatGlobal; else -> { s.tell(Component.text("Usage: /chat global|local|toggle", NamedTextColor.RED)); return } }
        s.chatGlobal = mode
        s.tell(Component.text(if (mode) "Chat mode: global (everyone on the network reads you)" else "Chat mode: local (only players on your backend)", NamedTextColor.GRAY))
    }

    private fun send(s: ClientSession, server: ClientServer, args: List<String>) {
        if (!server.settings.isAdmin(s.playerName, s.uuid)) { s.tell(Component.text("You are not allowed to do that.", NamedTextColor.RED)); return }
        if (args.size != 2) { s.tell(Component.text("Usage: /fission send <player> <backend>", NamedTextColor.RED)); return }
        val target = server.findByName(args[0]) ?: run { s.tell(Component.text("No such player: ${args[0]}", NamedTextColor.RED)); return }
        server.log("[master] ${s.playerName} sends ${target.playerName} to ${args[1]}")
        target.startSwitch(args[1]) { why -> s.tell(Component.text("Cannot send ${target.playerName} to ${args[1]}: $why", NamedTextColor.RED)) }
        s.tell(Component.text("Sending ${target.playerName} to ${args[1]}...", NamedTextColor.GRAY))
    }
}
