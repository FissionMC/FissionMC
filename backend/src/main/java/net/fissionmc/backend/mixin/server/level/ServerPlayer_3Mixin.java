package net.fissionmc.backend.mixin.server.level;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

/** Paper/CraftBukkit: sources/net/minecraft/server/level/ServerPlayer.java.patch (anonymous CommandSource of ServerPlayer#commandSource: getBukkitSender). */
@Mixin(targets = "net.minecraft.server.level.ServerPlayer$3")
public abstract class ServerPlayer_3Mixin {
    @Shadow @Final ServerPlayer this$0;

    // CraftBukkit start
    public org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack wrapper) {
        return this.this$0.getBukkitEntity();
    }
    // CraftBukkit end
}
