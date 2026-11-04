package net.fissionmc.backend

import net.fissionmc.craft.CraftServer
import net.minecraft.server.dedicated.DedicatedServer
import org.bukkit.plugin.PluginLoadOrder

/** Kotlin side of the thin Java mixin shims. All real logic lives here. */
object Hooks {
    private var craft: CraftServer? = null

    @JvmStatic
    fun onMain() {
        System.err.println("[FissionMC] loader active, vanilla main reached through Mixin")
    }

    @JvmStatic
    fun beforeLevel(server: DedicatedServer) {
        val c = CraftServer(server)
        craft = c
        c.loadPlugins()
        c.enablePlugins(PluginLoadOrder.STARTUP)
    }

    @JvmStatic
    fun afterLevel() {
        craft?.enablePlugins(PluginLoadOrder.POSTWORLD)
    }
}
