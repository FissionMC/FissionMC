package net.fissionmc.backend.mixin.server.commands;

import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/server/commands/DebugCommand.java.patch (Tracer) */
@Mixin(targets = "net.minecraft.server.commands.DebugCommand$Tracer")
public abstract class DebugCommand_TracerMixin {
    // Paper start
    public org.bukkit.command.CommandSender getBukkitSender(final CommandSourceStack wrapper) {
        throw new UnsupportedOperationException();
    }
    // Paper end
}
