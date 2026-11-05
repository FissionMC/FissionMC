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

/** Paper: sources/net/minecraft/world/level/block/LightningRodBlock.java.patch */
@SuppressWarnings("unused")
@Mixin(LightningRodBlock.class)
public abstract class LightningRodBlockMixin {
    @Inject(method = "onLightningStrike", at = @At("HEAD"), cancellable = true)
    private void fission$redstoneOn(final BlockState state, final Level level, final BlockPos pos, final CallbackInfo ci) {
        // Paper start - Call BlockRedstoneEvent
        if (!state.getValue(LightningRodBlock.POWERED)) {
            if (!CraftEventFactory.callBinaryRedstoneChange(level, pos, true)) {
                ci.cancel();
            }
        }
        // Paper end - Call BlockRedstoneEvent
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void fission$redstoneOff(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci) {
        // Paper start - Call BlockRedstoneEvent
        if (state.getValue(LightningRodBlock.POWERED)) {
            if (!CraftEventFactory.callBinaryRedstoneChange(level, pos, false)) {
                ci.cancel();
            }
        }
        // Paper end - Call BlockRedstoneEvent
    }
}
