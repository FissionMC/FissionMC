package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DriedGhastBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: DriedGhastBlock.java.patch - BlockGrowEvent (waterlogged hydration) and BlockFadeEvent (ghastling spawn). */
@Mixin(DriedGhastBlock.class)
public abstract class DriedGhastBlockMixin {
    @WrapOperation(method = "tickWaterlogged", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean paper$grow(final ServerLevel level, final BlockPos pos, final BlockState newState, final int flags, final Operation<Boolean> original) {
        return CraftEventFactory.handleBlockGrowEvent(level, pos, newState, flags); // Paper - Call BlockGrowEvent
    }

    @Inject(method = "spawnGhastling", at = @At("HEAD"), cancellable = true)
    private void paper$fade(final ServerLevel level, final BlockPos position, final BlockState state, final CallbackInfo ci) {
        if (CraftEventFactory.callBlockFadeEvent(level, position, state.getFluidState().createLegacyBlock()).isCancelled()) { // Paper - Call BlockFadeEvent
            ci.cancel();
        }
    }
}
