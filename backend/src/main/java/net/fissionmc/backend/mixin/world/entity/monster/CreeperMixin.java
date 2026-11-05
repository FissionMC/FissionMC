package net.fissionmc.backend.mixin.world.entity.monster;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/monster/Creeper.java.patch */
@Mixin(Creeper.class)
public abstract class CreeperMixin extends net.minecraft.world.entity.monster.Monster {
    private CreeperMixin() {
        super(null, null);
    }

    @Shadow private int swell;
    @Shadow private void spawnLingeringCloud() { throw new AssertionError(); }

    @Shadow @Final private static EntityDataAccessor<Boolean> DATA_IS_POWERED;
    @Shadow @Final private static EntityDataAccessor<Boolean> DATA_IS_IGNITED;

    @Shadow public abstract boolean isIgnited();
    @Shadow public abstract boolean isPowered();

    // Surface: public (see surface/members/entitytypes.txt); the loader widens it.
    private @Nullable Entity entityIgniter; // CraftBukkit
    // Done: CreeperPowerEvent, ExplosionPrimeEvent, CreeperIgniteEvent (ignite), entityIgniter, lingering cloud owner/reason.
    // TODO(behaviour): setTarget(reason)/killedEntityPreEvent overloads, ignited load without event, flint&steel unbreakable fix, config disableCreeperLingeringEffect.

    // CraftBukkit start
    public void setPowered(boolean powered) {
        ((Entity) (Object) this).getEntityData().set(CreeperMixin.DATA_IS_POWERED, powered);
    }
    // CraftBukkit end

    // Paper start - Call CreeperIgniteEvent
    public void setIgnited(boolean ignited) {
        if (isIgnited() != ignited) {
            com.destroystokyo.paper.event.entity.CreeperIgniteEvent event = new com.destroystokyo.paper.event.entity.CreeperIgniteEvent((org.bukkit.entity.Creeper) ((Entity) (Object) this).getBukkitEntity(), ignited);
            if (event.callEvent()) {
                ((Entity) (Object) this).getEntityData().set(CreeperMixin.DATA_IS_IGNITED, event.isIgnited());
            }
        }
    }
    // Paper end - Call CreeperIgniteEvent

    // CreeperPowerEvent
    @WrapOperation(method = "thunderHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/syncher/SynchedEntityData;set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V"))
    private void fission$powerEvent(final SynchedEntityData data, final EntityDataAccessor<Boolean> accessor, final Object value, final Operation<Void> original, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) LightningBolt lightningBolt) {
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callCreeperPowerEvent((Creeper) (Object) this, lightningBolt, org.bukkit.event.entity.CreeperPowerEvent.PowerCause.LIGHTNING).isCancelled()) {
            return;
        }
        original.call(data, accessor, value);
    }

    // CraftBukkit - entityIgniter
    @Inject(method = "mobInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Creeper;ignite()V"))
    private void fission$igniter(final net.minecraft.world.entity.player.Player player, final net.minecraft.world.InteractionHand hand, final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.InteractionResult> cir) {
        this.entityIgniter = player; // CraftBukkit
    }

    // Paper - Call CreeperIgniteEvent
    @Inject(method = "ignite", at = @At("HEAD"), cancellable = true)
    private void fission$ignite(final CallbackInfo ci) {
        this.setIgnited(true);
        ci.cancel();
    }

    // replaced body: explodeCreeper with ExplosionPrimeEvent
    @Inject(method = "explodeCreeper", at = @At("HEAD"), cancellable = true)
    private void fission$explode(final CallbackInfo ci) {
        if (this.level() instanceof ServerLevel level) {
            float explosionMultiplier = this.isPowered() ? 2.0F : 1.0F;
            org.bukkit.event.entity.ExplosionPrimeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callExplosionPrimeEvent(this, ((Creeper) (Object) this).explosionRadius * explosionMultiplier, false);
            if (!event.isCancelled()) {
                this.dead = true;
                level.explode(this, this.getX(), this.getY(), this.getZ(), event.getRadius(), event.getFire(), net.minecraft.world.level.Level.ExplosionInteraction.MOB); // CraftBukkit
                this.spawnLingeringCloud();
                this.triggerOnDeathMobEffects(level, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.EXPLODE); // CraftBukkit - add Bukkit remove cause
            } else {
                this.swell = 0;
                this.entityData.set(DATA_IS_IGNITED, false); // Paper
            }
        }
        ci.cancel();
    }

    // lingering cloud: owner + spawn reason
    @WrapOperation(method = "spawnLingeringCloud", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean fission$cloud(final net.minecraft.world.level.Level level, final net.minecraft.world.entity.Entity cloud, final Operation<Boolean> original) {
        ((net.minecraft.world.entity.AreaEffectCloud) cloud).setOwner(this); // CraftBukkit
        return level.addFreshEntity(cloud, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.EXPLOSION); // CraftBukkit
    }
}
