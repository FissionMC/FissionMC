package net.fissionmc.backend.mixin.world.level;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseCommandBlock;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/level/BaseCommandBlock.java.patch.
 * createSource(ServerLevel) is only widened (surface). Paper declares getBukkitSender/getLevel abstract (surface); the
 * implementations belong in CommandBlockEntity's and MinecartCommandBlock's BaseCommandBlock subclasses (blocks/entity areas).
 * FissionMC: Mixin cannot add abstract methods, so the runtime declarations throw until overridden.
 */
@Mixin(BaseCommandBlock.class)
public abstract class BaseCommandBlockMixin {
    // CraftBukkit start
    protected org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack wrapper) {
        throw new AbstractMethodError("BaseCommandBlock#getBukkitSender must be implemented by " + this.getClass().getName());
    }

    public ServerLevel getLevel() {
        throw new AbstractMethodError("BaseCommandBlock#getLevel must be implemented by " + this.getClass().getName());
    }
    // CraftBukkit end

    // FissionMC: lets BaseCommandBlock_CloseableCommandBlockSourceMixin reach the protected method from another package
    public org.bukkit.command.CommandSender fission$getBukkitSender(CommandSourceStack wrapper) {
        return this.getBukkitSender(wrapper);
    }
    // TODO(behaviour): performCommand/createSource hunks (per-world timeFormatPattern, trackOutput)
}
