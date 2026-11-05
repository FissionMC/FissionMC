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

/** Paper: sources/net/minecraft/world/level/block/SweetBerryBushBlock.java.patch */
@SuppressWarnings("unused")
@Mixin(SweetBerryBushBlock.class)
public abstract class SweetBerryBushBlockMixin {
    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean fission$grow(final ServerLevel level, final BlockPos pos, final BlockState state, final int flags, final Operation<Boolean> original, @com.llamalad7.mixinextras.sugar.Share("cancelled") final com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef cancelled) {
        boolean placed = CraftEventFactory.handleBlockGrowEvent(level, pos, state, flags); // CraftBukkit
        cancelled.set(!placed);
        return placed;
    }

    @Inject(method = "randomTick", cancellable = true, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V"))
    private void fission$growCancelled(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Share("cancelled") final com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef cancelled) {
        if (cancelled.get()) ci.cancel(); // CraftBukkit
    }

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void fission$entityInside(final BlockState state, final Level level, final BlockPos pos, final Entity entity, final InsideBlockEffectApplier effectApplier, final boolean isPrecise, final CallbackInfo ci) {
        if (!new io.papermc.paper.event.entity.EntityInsideBlockEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos)).callEvent()) { ci.cancel(); } // Paper - Add EntityInsideBlockEvent
    }

    @WrapOperation(method = "entityInside", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/damagesource/DamageSources;sweetBerryBush()Lnet/minecraft/world/damagesource/DamageSource;"))
    private net.minecraft.world.damagesource.DamageSource fission$damager(final net.minecraft.world.damagesource.DamageSources sources, final Operation<net.minecraft.world.damagesource.DamageSource> original, final BlockState state, final Level level, final BlockPos pos) {
        return original.call(sources).eventBlockDamager(level, pos); // CraftBukkit
    }

    // CraftBukkit start - PlayerHarvestBlockEvent (useWithoutItem is always MAIN_HAND)
    @WrapOperation(method = "useWithoutItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;dropFromBlockInteractLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/item/ItemInstance;Lnet/minecraft/world/entity/Entity;Ljava/util/function/BiConsumer;)Z"))
    private boolean fission$harvest(final ServerLevel serverLevel, final net.minecraft.resources.ResourceKey<?> table, final BlockPos pos, final BlockState state, final net.minecraft.world.level.block.entity.BlockEntity be,
                                     final net.minecraft.world.item.ItemInstance tool, final Entity interacting, final java.util.function.BiConsumer<ServerLevel, net.minecraft.world.item.ItemStack> consumer, final Operation<Boolean> original,
                                     final BlockState state2, final Level level, final BlockPos pos2, final Player player, @com.llamalad7.mixinextras.sugar.Share("cancelled") final com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef cancelled) {
        java.util.List<net.minecraft.world.item.ItemStack> drops = new java.util.ArrayList<>(); // Paper
        boolean result = original.call(serverLevel, table, pos, state, be, tool, interacting, (java.util.function.BiConsumer<ServerLevel, net.minecraft.world.item.ItemStack>) (serverlvl, itemStack) -> drops.add(itemStack)); // Paper
        org.bukkit.event.player.PlayerHarvestBlockEvent event = CraftEventFactory.callPlayerHarvestBlockEvent(level, pos, player, net.minecraft.world.InteractionHand.MAIN_HAND, drops);
        if (event.isCancelled()) {
            cancelled.set(true); // We need to return a success either way
            return result;
        }
        for (org.bukkit.inventory.ItemStack itemStack : event.getItemsHarvested()) {
            Block.popResource(level, pos, org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(itemStack));
        }
        return result;
    }

    @Inject(method = "useWithoutItem", cancellable = true, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;playSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
    private void fission$harvestCancelled(final BlockState state, final Level level, final BlockPos pos, final Player player, final net.minecraft.world.phys.BlockHitResult hitResult, final CallbackInfoReturnable<InteractionResult> cir, @com.llamalad7.mixinextras.sugar.Share("cancelled") final com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef cancelled) {
        if (cancelled.get()) cir.setReturnValue(InteractionResult.SUCCESS);
    }
    // CraftBukkit end
    // config: Spigot sweetBerryModifier not ported.
}
