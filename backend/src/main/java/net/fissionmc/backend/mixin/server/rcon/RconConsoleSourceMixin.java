package net.fissionmc.backend.mixin.server.rcon;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.rcon.RconConsoleSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/server/rcon/RconConsoleSource.java.patch */
@Mixin(RconConsoleSource.class)
public abstract class RconConsoleSourceMixin {
    @Shadow @Final private StringBuffer buffer;

    // CraftBukkit start
    // TODO(behaviour): Paper sets this through an added RconConsoleSource(MinecraftServer, SocketAddress) constructor (RconClient)
    private java.net.SocketAddress socketAddress;
    private final org.bukkit.craftbukkit.command.CraftRemoteConsoleCommandSender remoteConsole = new org.bukkit.craftbukkit.command.CraftRemoteConsoleCommandSender((RconConsoleSource) (Object) this);
    // CraftBukkit end

    // CraftBukkit start - Send a String
    public void sendMessage(String message) {
        this.buffer.append(message);
    }

    public org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack wrapper) {
        return this.remoteConsole;
    }
    // CraftBukkit end
}
