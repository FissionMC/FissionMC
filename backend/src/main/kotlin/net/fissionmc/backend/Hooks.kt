package net.fissionmc.backend

import io.papermc.paper.plugin.PluginInitializerManager
import net.fissionmc.craft.FissionOptions
import net.minecraft.server.dedicated.DedicatedServer
import org.bukkit.craftbukkit.CraftServer
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
        PluginInitializerManager.load(FissionOptions.get())
        c.loadPlugins()
        c.enablePlugins(PluginLoadOrder.STARTUP)
    }

    @JvmStatic
    fun afterLevel() {
        craft?.enablePlugins(PluginLoadOrder.POSTWORLD)
    }

    @JvmStatic
    fun beforeStop() {
        craft?.disablePlugins()
    }
}
