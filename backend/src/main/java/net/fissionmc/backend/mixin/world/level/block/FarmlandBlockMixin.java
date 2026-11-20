package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/level/block/FarmlandBlock.java.patch (MoistureChangeEvent, trample PlayerInteractEvent/EntityInteractEvent/EntityChangeBlockEvent, BlockFadeEvent).
 * Left: Paper-config random tick rates, isNearWater perf rewrite.
 */
@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin {
    @Shadow
    @Final
    private Block baseBlock;

    // CraftBukkit - moisture changes go through MoistureChangeEvent
    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean paper$moisture(final ServerLevel level, final BlockPos pos, final BlockState state, final int flags, final Operation<Boolean> original) {
        return CraftEventFactory.handleMoistureChangeEvent(level, pos, state, flags);
    }

    // CraftBukkit start - Interact soil (the trample is attempted from fallOn)
    @WrapOperation(method = "fallOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/FarmlandBlock;turnToBaseBlock(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void paper$trample(final FarmlandBlock self, final Entity entity, final BlockState state, final Level level, final BlockPos pos, final Operation<Void> original) {
        final org.bukkit.event.Cancellable cancellable;
        if (entity instanceof Player player) {
            cancellable = CraftEventFactory.callPlayerInteractEvent(player, org.bukkit.event.block.Action.PHYSICAL, pos, null, null, null);
        } else {
            cancellable = new org.bukkit.event.entity.EntityInteractEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos));
            level.getCraftServer().getPluginManager().callEvent((org.bukkit.event.entity.EntityInteractEvent) cancellable);
        }
        if (cancellable.isCancelled()) {
            return;
        }
        if (!CraftEventFactory.callEntityChangeBlockEvent(entity, pos, this.baseBlock.defaultBlockState())) {
            return;
        }
        original.call(self, entity, state, level, pos);
    }
    // CraftBukkit end

    @Inject(method = "turnToBaseBlock", at = @At("HEAD"), cancellable = true)
    private void paper$fade(final Entity sourceEntity, final BlockState state, final Level level, final BlockPos pos, final CallbackInfo ci) {
        if (sourceEntity == null && CraftEventFactory.callBlockFadeEvent(level, pos, this.baseBlock.defaultBlockState()).isCancelled()) {
            ci.cancel();
        }
    }
}
