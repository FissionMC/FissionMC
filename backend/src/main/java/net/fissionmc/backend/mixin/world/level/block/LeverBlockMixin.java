package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.redstone.Orientation;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/level/block/LeverBlock.java.patch */
@SuppressWarnings("unused")
@Mixin(LeverBlock.class)
public abstract class LeverBlockMixin {
    // Paper start - Call BlockRedstoneEvent
    @WrapWithCondition(method = {"useWithoutItem", "onExplosionHit"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/LeverBlock;pull(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)V"))
    private boolean fission$redstone(final LeverBlock self, final BlockState state, final Level level, final BlockPos pos, final @Nullable Player player) {
        boolean wasPowered = state.getValue(LeverBlock.POWERED);
        return CraftEventFactory.callBinaryRedstoneChange(level, pos, !wasPowered);
    }
    // Paper end - Call BlockRedstoneEvent
}
