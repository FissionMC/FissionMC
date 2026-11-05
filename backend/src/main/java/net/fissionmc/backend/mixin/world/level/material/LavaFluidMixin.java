package net.fissionmc.backend.mixin.world.level.material;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: sources/net/minecraft/world/level/material/LavaFluid.java.patch
 * Done: BlockIgniteEvent in randomTick, BlockFormEvent (stone) in spreadTo, explosion resistance.
 * TODO(behaviour): lavaHurt(savedPos) contact tracking. A cancelled ignite skips the placement but not the following `return` (Paper `continue`s).
 */
@Mixin(LavaFluid.class)
public abstract class LavaFluidMixin {
    private boolean fission$formed = true;

    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean fission$ignite(final ServerLevel level, final BlockPos target, final BlockState state, final Operation<Boolean> original, @Local(name = "pos") final BlockPos pos) {
        if (!level.getBlockState(target).is(Blocks.FIRE)) {
            if (org.bukkit.craftbukkit.event.CraftEventFactory.callBlockIgniteEvent(level, target, pos).isCancelled()) {
                return false;
            }
        }
        return original.call(level, target, state);
    }

    @WrapOperation(method = "spreadTo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean fission$form(final LevelAccessor level, final BlockPos pos, final BlockState state, final Operation<Boolean> original) {
        this.fission$formed = org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFormEvent(level.getMinecraftWorld(), pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        return this.fission$formed;
    }

    @WrapOperation(method = "spreadTo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/LavaFluid;fizz(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;)V"))
    private void fission$fizz(final LavaFluid self, final LevelAccessor level, final BlockPos pos, final Operation<Void> original) {
        if (this.fission$formed) original.call(self, level, pos);
    }
}
