package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: sources/net/minecraft/world/level/block/LiquidBlock.java.patch
 * Done: BlockFormEvent (cobblestone/obsidian/basalt). config: water-over-lava-flow-speed (getFlowSpeed; default = vanilla delay), so not ported.
 */
@Mixin(LiquidBlock.class)
public abstract class LiquidBlockMixin {
    private boolean fission$formed = true;

    @WrapOperation(method = "shouldSpreadLiquid", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean fission$form(final Level level, final BlockPos pos, final BlockState state, final Operation<Boolean> original) {
        this.fission$formed = org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFormEvent(level, pos, state, Block.UPDATE_ALL);
        return this.fission$formed;
    }

    @WrapOperation(method = "shouldSpreadLiquid", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/LiquidBlock;fizz(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;)V"))
    private void fission$fizz(final LiquidBlock self, final LevelAccessor level, final BlockPos pos, final Operation<Void> original) {
        if (this.fission$formed) original.call(self, level, pos);
    }
}
