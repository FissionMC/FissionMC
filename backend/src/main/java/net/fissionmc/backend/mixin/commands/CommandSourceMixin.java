package net.fissionmc.backend.mixin.commands;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/commands/CommandSource.java.patch */
@Mixin(CommandSource.class)
public interface CommandSourceMixin {
    // CraftBukkit: abstract in Paper (surface: abstract). At run time a default that throws, so implementers
    // without their override fail loudly. Implementers that need overrides (owned by other areas):
    // MinecraftServer, ServerPlayer$3, RconConsoleSource, BaseCommandBlock$CloseableCommandBlockSource,
    // DebugCommand$Tracer; CommandSource$1 (NULL) is in CommandSource_1Mixin.
    default org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack wrapper) { // CraftBukkit
        throw new UnsupportedOperationException("getBukkitSender not implemented by " + this.getClass().getName());
    }
}
