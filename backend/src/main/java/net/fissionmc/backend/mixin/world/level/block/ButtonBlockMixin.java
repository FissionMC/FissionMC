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

/** Paper: sources/net/minecraft/world/level/block/ButtonBlock.java.patch */
@SuppressWarnings("unused")
@Mixin(ButtonBlock.class)
public abstract class ButtonBlockMixin {
    // Paper start - Call BlockRedstoneEvent
    @WrapWithCondition(method = {"useWithoutItem", "onExplosionHit"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/ButtonBlock;press(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)V"))
    private boolean fission$redstone(final ButtonBlock self, final BlockState state, final Level level, final BlockPos pos, final @Nullable Player player) {
        return CraftEventFactory.callBinaryRedstoneChange(level, pos, true);
    }
    // Paper end - Call BlockRedstoneEvent

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void fission$entityInside(final BlockState state, final Level level, final BlockPos pos, final Entity entity, final InsideBlockEffectApplier effectApplier, final boolean isPrecise, final CallbackInfo ci) {
        if (!new io.papermc.paper.event.entity.EntityInsideBlockEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos)).callEvent()) { ci.cancel(); } // Paper - Add EntityInsideBlockEvent
    }

    @Inject(method = "checkPressed", cancellable = true, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private void fission$checkPressed(final BlockState state, final Level level, final BlockPos pos, final CallbackInfo ci,
                                       @Local final net.minecraft.world.entity.projectile.arrow.AbstractArrow firstArrow, @Local(ordinal = 0) final boolean shouldBePressed) {
        // CraftBukkit start - Call interact event when arrows turn on wooden buttons
        if (shouldBePressed) {
            org.bukkit.block.Block bukkitBlock = org.bukkit.craftbukkit.block.CraftBlock.at(level, pos);
            org.bukkit.event.entity.EntityInteractEvent event = new org.bukkit.event.entity.EntityInteractEvent(firstArrow.getBukkitEntity(), bukkitBlock);
            if (!event.callEvent()) {
                ci.cancel();
                return;
            }
        }
        // CraftBukkit end
        // Paper start - Call BlockRedstoneEvent
        if (!CraftEventFactory.callBinaryRedstoneChange(level, pos, shouldBePressed)) {
            ci.cancel();
        }
        // Paper end - Call BlockRedstoneEvent
    }
}
