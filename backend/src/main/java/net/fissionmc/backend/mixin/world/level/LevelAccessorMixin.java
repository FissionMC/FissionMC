package net.fissionmc.backend.mixin.world.level;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/level/LevelAccessor.java.patch (getMinecraftWorld is abstract in Paper).
 * FissionMC: declared here so invokeinterface LevelAccessor.getMinecraftWorld resolves at runtime; ServerLevelAccessorMixin's
 * default (and other implementors) override it.
 */
@Mixin(LevelAccessor.class)
public interface LevelAccessorMixin {
    default ServerLevel getMinecraftWorld() {
        throw new AbstractMethodError("FissionMC: " + this.getClass().getName() + " does not implement getMinecraftWorld");
    }
}
