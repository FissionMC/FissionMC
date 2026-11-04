package net.fissionmc.backend.mixin.server.dedicated;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/server/dedicated/DedicatedServer.java.patch */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {
    @Shadow public abstract DedicatedServerProperties getProperties();

    // Paper start - Add path to plugins folder
    public java.io.File getPluginsFolder() {
        return (java.io.File) ((DedicatedServer) (Object) this).options.valueOf("plugins");
    }
    // Paper end

    // CraftBukkit start
    public boolean isDebugging() {
        return this.getProperties().debug;
    }

    public org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack wrapper) {
        return ((DedicatedServer) (Object) this).console;
    }
    // CraftBukkit end
}
