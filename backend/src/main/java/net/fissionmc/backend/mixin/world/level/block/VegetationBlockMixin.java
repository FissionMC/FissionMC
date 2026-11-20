package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: VegetationBlock.java.patch - BlockPhysicsEvent when the plant would pop off. Cancelling the event keeps the plant:
 * canSurvive is treated as true (Paper falls through to super.updateShape in that case, which is the same branch).
 */
@Mixin(VegetationBlock.class)
public abstract class VegetationBlockMixin {
    @WrapOperation(method = "updateShape", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;canSurvive(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean paper$physicsEvent(final BlockState state, final LevelReader level, final BlockPos pos, final Operation<Boolean> original) {
        final boolean survives = original.call(state, level, pos);
        if (survives) {
            return true;
        }
        // Suppress during worldgen
        return level instanceof ServerLevel serverLevel && serverLevel.hasPhysicsEvent && CraftEventFactory.callBlockPhysicsEvent(serverLevel, pos).isCancelled();
    }
}
