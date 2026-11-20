package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: CauldronBlock.java.patch - CauldronLevelChangeEvent (NATURAL_FILL) for rain, powder snow and dripstone fills. */
@Mixin(CauldronBlock.class)
public abstract class CauldronBlockMixin {
    @WrapOperation(method = "handlePrecipitation", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean paper$naturalFillPrecipitation(final Level level, final BlockPos pos, final BlockState newState, final Operation<Boolean> original) {
        return CraftEventFactory.handleCauldronLevelChangeEvent(level, pos, newState, null, CauldronLevelChangeEvent.ChangeReason.NATURAL_FILL);
    }

    @WrapOperation(method = "receiveStalactiteDrip", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean paper$naturalFillDrip(final Level level, final BlockPos pos, final BlockState newState, final Operation<Boolean> original) {
        return CraftEventFactory.handleCauldronLevelChangeEvent(level, pos, newState, null, CauldronLevelChangeEvent.ChangeReason.NATURAL_FILL);
    }
}
