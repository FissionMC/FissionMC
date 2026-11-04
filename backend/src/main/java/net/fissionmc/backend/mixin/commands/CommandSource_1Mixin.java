package net.fissionmc.backend.mixin.commands;

import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/commands/CommandSource.java.patch (CommandSource.NULL, the anonymous CommandSource$1) */
@Mixin(targets = "net.minecraft.commands.CommandSource$1")
public abstract class CommandSource_1Mixin {
    // CraftBukkit start
    public org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack stack) {
        return io.papermc.paper.brigadier.NullCommandSender.INSTANCE; // Paper - expose a no-op CommandSender
    }
    // CraftBukkit end
}
