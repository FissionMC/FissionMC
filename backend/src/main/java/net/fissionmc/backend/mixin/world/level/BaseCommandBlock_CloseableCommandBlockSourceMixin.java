package net.fissionmc.backend.mixin.world.level;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.level.BaseCommandBlock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/BaseCommandBlock.java.patch (CloseableCommandBlockSource) */
@Mixin(targets = "net.minecraft.world.level.BaseCommandBlock$CloseableCommandBlockSource")
public abstract class BaseCommandBlock_CloseableCommandBlockSourceMixin {
    @Shadow @Final BaseCommandBlock this$0;

    // CraftBukkit start
    public org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack wrapper) {
        return ((BaseCommandBlockMixin) (Object) this.this$0).fission$getBukkitSender(wrapper); // BaseCommandBlock.this.getBukkitSender(wrapper)
    }
    // CraftBukkit end
}
