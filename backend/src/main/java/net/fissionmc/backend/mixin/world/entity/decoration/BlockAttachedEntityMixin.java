package net.fissionmc.backend.mixin.world.entity.decoration;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/world/entity/decoration/BlockAttachedEntity.java.patch
 * Done: HangingBreakEvent/EntityBreakEvent in tick (physics/obstruction), hurtServer and move; discard cause; push disabled.
 * config: spigot hangingTickFrequency + tick offset (default 100 = vanilla). TODO(behaviour): addAdditionalSaveData(output, includeAll).
 */
@Mixin(BlockAttachedEntity.class)
public abstract class BlockAttachedEntityMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/decoration/BlockAttachedEntity;discard()V"), cancellable = true)
    private void fission$breakEvent(final CallbackInfo ci) {
        final BlockAttachedEntity self = (BlockAttachedEntity) (Object) this;
        final io.papermc.paper.event.entity.EntityBreakEvent.RemoveCause cause;
        if (!self.level().getBlockState(self.blockPosition()).isAir()) {
            cause = io.papermc.paper.event.entity.EntityBreakEvent.RemoveCause.OBSTRUCTION;
        } else {
            cause = io.papermc.paper.event.entity.EntityBreakEvent.RemoveCause.PHYSICS;
        }
        boolean cancelled = false;
        if (self.getBukkitEntity() instanceof org.bukkit.entity.Hanging hanging) {
            final org.bukkit.event.hanging.HangingBreakEvent bukkitEvent = new org.bukkit.event.hanging.HangingBreakEvent(hanging, org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.valueOf(cause.name()));
            cancelled = !bukkitEvent.callEvent();
        }
        final io.papermc.paper.event.entity.EntityBreakEvent event = new io.papermc.paper.event.entity.EntityBreakEvent(self.getBukkitEntity(), cause);
        event.setCancelled(cancelled);
        cancelled = !event.callEvent();
        if (self.isRemoved() || cancelled) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/decoration/BlockAttachedEntity;discard()V"))
    private void fission$discardCause(final BlockAttachedEntity self, final Operation<Void> original) {
        self.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DROP); // CraftBukkit - add Bukkit remove cause
    }

    @Inject(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/decoration/BlockAttachedEntity;kill(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)V"), cancellable = true)
    private void fission$hurtBreak(final ServerLevel level, final DamageSource source, final float damage, final CallbackInfoReturnable<Boolean> cir) {
        final BlockAttachedEntity self = (BlockAttachedEntity) (Object) this;
        boolean cancelled = false;
        Entity damager = source.getDirectEntity();
        org.bukkit.damage.DamageSource craftDamageSource = new org.bukkit.craftbukkit.damage.CraftDamageSource(source);
        if (self.getBukkitEntity() instanceof org.bukkit.entity.Hanging hanging) {
            final org.bukkit.event.hanging.HangingBreakEvent bukkitEvent;
            if (damager != null) {
                bukkitEvent = new org.bukkit.event.hanging.HangingBreakByEntityEvent(hanging, damager.getBukkitEntity(), craftDamageSource, source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) ? org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.EXPLOSION : org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.ENTITY);
            } else {
                bukkitEvent = new org.bukkit.event.hanging.HangingBreakEvent(hanging, source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) ? org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.EXPLOSION : org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.DEFAULT);
            }
            cancelled = !bukkitEvent.callEvent();
        }
        final io.papermc.paper.event.entity.EntityBreakEvent event;
        if (damager != null) {
            event = new io.papermc.paper.event.entity.EntityBreakByEntityEvent(self.getBukkitEntity(), damager.getBukkitEntity(), craftDamageSource, source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) ? io.papermc.paper.event.entity.EntityBreakEvent.RemoveCause.EXPLOSION : io.papermc.paper.event.entity.EntityBreakEvent.RemoveCause.ENTITY);
        } else {
            event = new io.papermc.paper.event.entity.EntityBreakEvent(self.getBukkitEntity(), source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) ? io.papermc.paper.event.entity.EntityBreakEvent.RemoveCause.EXPLOSION : io.papermc.paper.event.entity.EntityBreakEvent.RemoveCause.DEFAULT);
        }
        event.setCancelled(cancelled);
        cancelled = !event.callEvent();
        if (self.isRemoved() || cancelled) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/decoration/BlockAttachedEntity;kill(Lnet/minecraft/server/level/ServerLevel;)V"), cancellable = true)
    private void fission$moveBreak(final MoverType moverType, final Vec3 delta, final CallbackInfo ci) {
        final BlockAttachedEntity self = (BlockAttachedEntity) (Object) this;
        if (self.getBukkitEntity() instanceof org.bukkit.entity.Hanging hanging) {
            org.bukkit.event.hanging.HangingBreakEvent event = new org.bukkit.event.hanging.HangingBreakEvent(hanging, org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.PHYSICS);
            self.level().getCraftServer().getPluginManager().callEvent(event);
            if (self.isRemoved() || event.isCancelled()) {
                ci.cancel();
            }
        }
    }

    // CraftBukkit - push no longer kills (not needed)
    @Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true)
    private void fission$noPush(final double xa, final double ya, final double za, final CallbackInfo ci) {
        ci.cancel();
    }
}
