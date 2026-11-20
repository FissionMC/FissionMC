package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.NyliumBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: NyliumBlock.java.patch - BlockFadeEvent before the block changes (cancelled: the change is skipped). */
@Mixin(NyliumBlock.class)
public abstract class NyliumBlockMixin {
    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean paper$fade(final ServerLevel level, final BlockPos pos, final BlockState newState, final Operation<Boolean> original) {
        if (CraftEventFactory.callBlockFadeEvent(level, pos, newState).isCancelled()) { // CraftBukkit
            return false;
        }
        return original.call(level, pos, newState);
    }
}
