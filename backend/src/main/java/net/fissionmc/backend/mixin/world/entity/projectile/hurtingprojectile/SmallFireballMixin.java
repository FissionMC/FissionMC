package net.fissionmc.backend.mixin.world.entity.projectile.hurtingprojectile;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.BlockHitResult;

/** Paper: sources/net/minecraft/world/entity/projectile/hurtingprojectile/SmallFireball.java.patch */
@Mixin(SmallFireball.class)
public abstract class SmallFireballMixin extends Fireball {
    protected SmallFireballMixin(final EntityType<? extends Fireball> type, final Level level) {
        super(type, level);
    }

    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/Vec3;)V", at = @At("TAIL"))
    private void fission$incendiary(Level level, LivingEntity mob, Vec3 direction, CallbackInfo ci) {
        // CraftBukkit start
        if (this.getOwner() != null && this.getOwner() instanceof Mob) {
            this.isIncendiary = (level instanceof ServerLevel serverLevel) && serverLevel.getGameRules().get(GameRules.MOB_GRIEFING);
        }
        // CraftBukkit end
    }

    @WrapOperation(method = "onHitEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;igniteForSeconds(F)V"))
    private void fission$combust(Entity entity, float seconds, Operation<Void> original) {
        // CraftBukkit start - Entity damage by entity event + combust event
        org.bukkit.event.entity.EntityCombustByEntityEvent event = new org.bukkit.event.entity.EntityCombustByEntityEvent(this.getBukkitEntity(), entity.getBukkitEntity(), seconds);
        entity.level().getCraftServer().getPluginManager().callEvent(event);

        if (!event.isCancelled()) {
            entity.igniteForSeconds(event.getDuration(), false);
        }
        // CraftBukkit end
    }

    @Inject(method = "onHitBlock", at = @At("HEAD"), cancellable = true)
    private void fission$onHitBlock(BlockHitResult hitResult, CallbackInfo ci) {
        ci.cancel();
        super.onHitBlock(hitResult);
        if (this.level() instanceof ServerLevel serverLevel) {
            Entity owner = this.getOwner();
            if (this.isIncendiary) { // CraftBukkit
                BlockPos pos = hitResult.getBlockPos().relative(hitResult.getDirection());
                if (this.level().isEmptyBlock(pos) && !org.bukkit.craftbukkit.event.CraftEventFactory.callBlockIgniteEvent(this.level(), pos, this).isCancelled()) { // CraftBukkit
                    this.level().setBlockAndUpdate(pos, BaseFireBlock.getState(this.level(), pos));
                }
            }
        }
    }

    @Redirect(method = "onHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/hurtingprojectile/SmallFireball;discard()V"))
    private void fission$discardHit(SmallFireball instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
    }
}
