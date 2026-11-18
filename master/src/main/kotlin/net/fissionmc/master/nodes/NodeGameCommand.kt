package net.fissionmc.master.nodes

import net.fissionmc.master.client.ClientSession
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/** `/fission node ...` for admins in game: the same commands as the master console (`node ...`), see [NodeAdmin.command]. */
object NodeGameCommand {
    fun run(s: ClientSession, args: List<String>) {
        if (!s.isAdmin) { s.tell(Component.text("You are not allowed to do that.", NamedTextColor.RED)); return }
        val admin = NodeAdmin.current ?: run { s.tell(Component.text("Node link security is off on this master: there are no keys to manage.", NamedTextColor.RED)); return }
        admin.command(args, "admin ${s.playerName}") { line -> s.tell(Component.text(line, NamedTextColor.GRAY)) }
    }
}
