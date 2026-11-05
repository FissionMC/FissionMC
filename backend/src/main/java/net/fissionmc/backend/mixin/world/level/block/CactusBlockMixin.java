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

/** Paper: sources/net/minecraft/world/level/block/CactusBlock.java.patch */
@SuppressWarnings("unused")
@Mixin(CactusBlock.class)
public abstract class CactusBlockMixin {
    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean fission$growFlower(final ServerLevel level, final BlockPos pos, final BlockState state, final Operation<Boolean> original) {
        return CraftEventFactory.handleBlockGrowEvent(level, pos, state, Block.UPDATE_ALL); // Paper - block grow event
    }

    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", ordinal = 1, target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean fission$grow(final ServerLevel level, final BlockPos pos, final BlockState state, final Operation<Boolean> original,
                                  @com.llamalad7.mixinextras.sugar.Share("cancelled") final com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef cancelled) {
        boolean placed = CraftEventFactory.handleBlockGrowEvent(level, pos, state, Block.UPDATE_ALL); // Paper - block grow event
        cancelled.set(!placed);
        return placed;
    }

    @Inject(method = "randomTick", cancellable = true, at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private void fission$growCancelled(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci,
                                        @com.llamalad7.mixinextras.sugar.Share("cancelled") final com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef cancelled) {
        if (cancelled.get()) ci.cancel(); // Paper - block grow event
    }

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void fission$entityInside(final BlockState state, final Level level, final BlockPos pos, final Entity entity, final InsideBlockEffectApplier effectApplier, final boolean isPrecise, final CallbackInfo ci) {
        if (!new io.papermc.paper.event.entity.EntityInsideBlockEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos)).callEvent()) { ci.cancel(); } // Paper - Add EntityInsideBlockEvent
    }

    @WrapOperation(method = "entityInside", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/damagesource/DamageSources;cactus()Lnet/minecraft/world/damagesource/DamageSource;"))
    private net.minecraft.world.damagesource.DamageSource fission$damager(final net.minecraft.world.damagesource.DamageSources sources, final Operation<net.minecraft.world.damagesource.DamageSource> original, final BlockState state, final Level level, final BlockPos pos) {
        return original.call(sources).eventBlockDamager(level, pos); // CraftBukkit
    }
    // config: Paper maxGrowthHeight.cactus (default 3) not ported.
}
