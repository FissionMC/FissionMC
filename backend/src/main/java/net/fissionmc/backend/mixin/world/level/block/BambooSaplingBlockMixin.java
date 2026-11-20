package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: BambooSaplingBlock.java.patch - BlockSpreadEvent for the first bamboo stalk. */
@Mixin(BambooSaplingBlock.class)
public abstract class BambooSaplingBlockMixin {
    @WrapOperation(method = "growBamboo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean paper$spread(final Level level, final BlockPos target, final BlockState state, final Operation<Boolean> original, @Local(argsOnly = true) final BlockPos pos) {
        return CraftEventFactory.handleBlockSpreadEvent(level, pos, target, state, Block.UPDATE_ALL); // CraftBukkit - BlockSpreadEvent
    }
}
