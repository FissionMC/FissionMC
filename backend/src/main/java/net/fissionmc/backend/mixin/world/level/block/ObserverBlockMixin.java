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

/** Paper: sources/net/minecraft/world/level/block/ObserverBlock.java.patch */
@SuppressWarnings("unused")
@Mixin(ObserverBlock.class)
public abstract class ObserverBlockMixin {
    // Paper start - Call BlockRedstoneEvent
    @Inject(method = "tick", cancellable = true, at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private void fission$redstoneOff(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci) {
        if (!CraftEventFactory.callBinaryRedstoneChange(level, pos, false)) ci.cancel();
    }

    @Inject(method = "tick", cancellable = true, at = @At(value = "INVOKE", ordinal = 1, target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private void fission$redstoneOn(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci) {
        if (!CraftEventFactory.callBinaryRedstoneChange(level, pos, true)) ci.cancel();
    }
    // Paper end - Call BlockRedstoneEvent
}
