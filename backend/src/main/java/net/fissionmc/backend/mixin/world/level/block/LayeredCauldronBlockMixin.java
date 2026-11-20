package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fissionmc.backend.blocks.PaperCauldrons;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: LayeredCauldronBlock.java.patch - CauldronLevelChangeEvent (NATURAL_FILL, EXTINGUISH, UNKNOWN). */
@Mixin(LayeredCauldronBlock.class)
public abstract class LayeredCauldronBlockMixin {
    @WrapOperation(method = "handlePrecipitation", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean paper$naturalFillPrecipitation(final Level level, final BlockPos pos, final BlockState newState, final Operation<Boolean> original) {
        return CraftEventFactory.handleCauldronLevelChangeEvent(level, pos, newState, null, CauldronLevelChangeEvent.ChangeReason.NATURAL_FILL);
    }

    @WrapOperation(method = "receiveStalactiteDrip", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean paper$naturalFillDrip(final Level level, final BlockPos pos, final BlockState newState, final Operation<Boolean> original) {
        return CraftEventFactory.handleCauldronLevelChangeEvent(level, pos, newState, null, CauldronLevelChangeEvent.ChangeReason.NATURAL_FILL);
    }

    // Paper - lowerFillLevel(state, level, pos) delegates to the event version (reason UNKNOWN, or EXTINGUISH from entityInside)
    @Inject(method = "lowerFillLevel(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private static void paper$lowerFillLevel(final BlockState state, final Level level, final BlockPos pos, final CallbackInfo ci) {
        final Entity entity = PaperCauldrons.entity;
        PaperCauldrons.lowerFillLevel(state, level, pos, entity,
            entity != null ? CauldronLevelChangeEvent.ChangeReason.EXTINGUISH : CauldronLevelChangeEvent.ChangeReason.UNKNOWN);
        ci.cancel();
    }

    // Paper - track the burning entity for the EXTINGUISH event (lambda inside entityInside's runBefore)
    @Inject(method = "lambda$entityInside$0", at = @At("HEAD"))
    private void paper$trackEntity(final ServerLevel serverLevel, final BlockPos blockPos, final BlockState state, final Level level, final Entity entity, final CallbackInfo ci) {
        PaperCauldrons.entity = entity;
    }

    @Inject(method = "lambda$entityInside$0", at = @At("RETURN"))
    private void paper$untrackEntity(final ServerLevel serverLevel, final BlockPos blockPos, final BlockState state, final Level level, final Entity entity, final CallbackInfo ci) {
        PaperCauldrons.entity = null;
    }
}
