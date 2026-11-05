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

/** Paper: sources/net/minecraft/world/level/block/TargetBlock.java.patch */
@SuppressWarnings("unused")
@Mixin(TargetBlock.class)
public abstract class TargetBlockMixin {
    @Shadow @org.spongepowered.asm.mixin.Final private static net.minecraft.world.level.block.state.properties.IntegerProperty OUTPUT_POWER;
    @Shadow private static int updateRedstoneOutput(final net.minecraft.world.level.LevelAccessor level, final BlockState state, final net.minecraft.world.phys.BlockHitResult hitResult, final Entity entity) { throw new AssertionError(); }
    @Shadow private static int getRedstoneStrength(final net.minecraft.world.phys.BlockHitResult hitResult, final net.minecraft.world.phys.Vec3 hitLocation) { throw new AssertionError(); }
    @Shadow private static void setOutputPower(final net.minecraft.world.level.LevelAccessor level, final BlockState state, final int outputStrength, final BlockPos pos, final int duration) { throw new AssertionError(); }

    // Paper start - Add TargetHitEvent (replaced body: criteria are awarded in updateRedstoneOutput)
    @Inject(method = "onProjectileHit", at = @At("HEAD"), cancellable = true)
    private void fission$onProjectileHit(final Level level, final BlockState state, final net.minecraft.world.phys.BlockHitResult hitResult, final net.minecraft.world.entity.projectile.Projectile projectile, final CallbackInfo ci) {
        int outputStrength = updateRedstoneOutput(level, state, hitResult, projectile);
        ci.cancel();
    }

    private static void fission$awardTargetHitCriteria(net.minecraft.world.entity.projectile.Projectile projectile, net.minecraft.world.phys.BlockHitResult hitResult, int outputStrength) {
        // Paper end - Add TargetHitEvent
        if (projectile.getOwner() instanceof net.minecraft.server.level.ServerPlayer playerOwner) {
            playerOwner.awardStat(net.minecraft.stats.Stats.TARGET_HIT);
            net.minecraft.advancements.triggers.CriteriaTriggers.TARGET_BLOCK_HIT.trigger(playerOwner, projectile, hitResult.getLocation(), outputStrength);
        }
    }

    @Inject(method = "updateRedstoneOutput", at = @At("HEAD"), cancellable = true)
    private static void fission$updateRedstoneOutput(final net.minecraft.world.level.LevelAccessor level, final BlockState state, final net.minecraft.world.phys.BlockHitResult hitResult, final Entity entity, final CallbackInfoReturnable<Integer> cir) {
        int redstoneStrength = getRedstoneStrength(hitResult, hitResult.getLocation());
        int duration = entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow ? 20 : 8;
        // Paper start - Add TargetHitEvent
        boolean shouldAward = false;
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile) {
            final org.bukkit.craftbukkit.block.CraftBlock craftBlock = org.bukkit.craftbukkit.block.CraftBlock.at(level, hitResult.getBlockPos());
            final org.bukkit.block.BlockFace blockFace = org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(hitResult.getDirection());
            final io.papermc.paper.event.block.TargetHitEvent targetHitEvent = new io.papermc.paper.event.block.TargetHitEvent((org.bukkit.entity.Projectile) entity.getBukkitEntity(), craftBlock, blockFace, redstoneStrength);
            if (targetHitEvent.callEvent()) {
                redstoneStrength = targetHitEvent.getSignalStrength();
                shouldAward = true;
            } else {
                cir.setReturnValue(redstoneStrength);
                return;
            }
        }
        // Paper end - Add TargetHitEvent
        if (!level.getBlockTicks().hasScheduledTick(hitResult.getBlockPos(), state.getBlock())) {
            setOutputPower(level, state, redstoneStrength, hitResult.getBlockPos(), duration);
        }

        // Paper start - Award Hit Criteria after Block Update
        if (shouldAward) {
            fission$awardTargetHitCriteria((net.minecraft.world.entity.projectile.Projectile) entity, hitResult, redstoneStrength);
        }
        // Paper end - Award Hit Criteria after Block Update
        cir.setReturnValue(redstoneStrength);
    }

    // Paper start - Call BlockRedstoneEvent
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "setOutputPower", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static int fission$setOutputPower(int outputStrength, final net.minecraft.world.level.LevelAccessor level, final BlockState state, final int outputStrength2, final BlockPos pos, final int duration) {
        if (state.getValue(OUTPUT_POWER) != outputStrength) {
            outputStrength = CraftEventFactory.callRedstoneChange(level, pos, state.getValue(OUTPUT_POWER), outputStrength).getNewCurrent();
        }
        return outputStrength;
    }

    @Inject(method = "tick", cancellable = true, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private void fission$tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci) {
        org.bukkit.event.block.BlockRedstoneEvent event = CraftEventFactory.callRedstoneChange(level, pos, state.getValue(OUTPUT_POWER), 0);
        if (event.getNewCurrent() > 0) {
            ci.cancel();
        }
    }
    // Paper end - Call BlockRedstoneEvent
}
