package net.fissionmc.backend.mixin.world.entity.projectile.arrow;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/projectile/arrow/AbstractArrow.java.patch */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin extends Projectile {
    @Shadow private IntOpenHashSet piercingIgnoreEntityIds;
    @Shadow private int life;
    @Shadow public int shakeTime;
    @Shadow public AbstractArrow.Pickup pickup;

    @Shadow public abstract byte getPierceLevel();
    @Shadow protected abstract boolean isInGround();
    @Shadow public abstract boolean isNoPhysics();
    @Shadow protected abstract ItemStack getPickupItem();
    @Shadow protected abstract boolean tryPickup(Player player);
    @Shadow public abstract boolean isCritArrow();

    // CraftBukkit - the owner handed to the (mob) constructor, set before the rest of things (SPIGOT-7744)
    private static final ThreadLocal<LivingEntity> fission$ctorOwner = new ThreadLocal<>();

    protected AbstractArrowMixin(final EntityType<? extends Projectile> type, final Level level) {
        super(type, level);
    }

    // CraftBukkit start - handle the owner before the rest of things
    @Redirect(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getX()D"))
    private static double fission$captureOwner(LivingEntity mob) {
        fission$ctorOwner.set(mob); // FissionMC: remember the owner for the delegated constructor
        return mob.getX();
    }

    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;DDDLnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V", shift = At.Shift.AFTER))
    private void fission$setOwnerEarly(CallbackInfo ci) {
        final LivingEntity ownerEntity = fission$ctorOwner.get();
        if (ownerEntity != null) {
            fission$ctorOwner.remove();
            this.setOwner(ownerEntity);
        }
        // CraftBukkit end
    }
    // vanilla then calls setOwner(mob) again in the (mob) constructor; that is idempotent (SPIGOT-7744 moved it)

    // Paper start - Fix loophole for arrow immunity
    @WrapOperation(method = {"lerpMotion", "startFalling"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;life:I", opcode = org.objectweb.asm.Opcodes.PUTFIELD))
    private void fission$arrowImmunity(AbstractArrow instance, int value, Operation<Void> original) {
        if (!this.level().paperConfig().entities.spawning.maxArrowDespawnInvulnerability.enabled()) {
            original.call(instance, value);
        }
    }
    // Paper end - Fix loophole for arrow immunity

    @Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;inGroundTime:I", opcode = org.objectweb.asm.Opcodes.PUTFIELD, ordinal = 1))
    private void fission$tickLifeRegardless(CallbackInfo ci) {
        // Paper start - tick life regardless after X seconds
        final io.papermc.paper.configuration.type.number.IntOr.Disabled maxArrowDespawnInvulnerability = this.level().paperConfig().entities.spawning.maxArrowDespawnInvulnerability;
        if (maxArrowDespawnInvulnerability.enabled() && this.tickCount > maxArrowDespawnInvulnerability.intValue()) this.tickDespawn();
        // Paper end - tick life regardless after X seconds
    }

    @Shadow protected abstract void tickDespawn();

    @Redirect(method = {"stepMoveAndHit", "hitTargetsOrDeflectSelf"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;hitTargetOrDeflectSelf(Lnet/minecraft/world/phys/HitResult;)Lnet/minecraft/world/entity/projectile/ProjectileDeflection;"))
    private ProjectileDeflection fission$preHit(AbstractArrow instance, HitResult hitResult) {
        return instance.preHitTargetOrDeflectSelf(hitResult); // CraftBukkit - projectile hit event
    }

    // Paper start - Fix cancelling ProjectileHitEvent for piercing arrows
    @Override
    public ProjectileDeflection preHitTargetOrDeflectSelf(HitResult hitResult) {
        if (hitResult instanceof EntityHitResult entityHitResult && this.hitCancelled && this.getPierceLevel() > 0) {
            if (this.piercingIgnoreEntityIds == null) {
                this.piercingIgnoreEntityIds = new IntOpenHashSet(5);
            }
            this.piercingIgnoreEntityIds.add(entityHitResult.getEntity().getId());
        }
        return super.preHitTargetOrDeflectSelf(hitResult);
    }
    // Paper end - Fix cancelling ProjectileHitEvent for piercing arrows

    @Inject(method = "tickDespawn", at = @At("HEAD"), cancellable = true)
    private void fission$tickDespawn(CallbackInfo ci) {
        ci.cancel();
        this.life++;
        if (this.life >= (this.pickup == AbstractArrow.Pickup.CREATIVE_ONLY ? this.level().paperConfig().entities.spawning.creativeArrowDespawnRate.value() : (this.pickup == AbstractArrow.Pickup.DISALLOWED ? this.level().paperConfig().entities.spawning.nonPlayerArrowDespawnRate.value() : (((Object) this instanceof ThrownTrident) ? this.level().spigotConfig.tridentDespawnRate : this.level().spigotConfig.arrowDespawnRate)))) { // Spigot // Paper - Configurable non-player arrow despawn rate; TODO: Extract this to init?
            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
        }
    }

    // Paper start - add push source entity param
    @Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true)
    private void fission$pushDelegate(double xa, double ya, double za, CallbackInfo ci) {
        ci.cancel();
        this.push(xa, ya, za, null);
    }

    @Override
    public void push(final double xa, final double ya, final double za, final @Nullable Entity pushingEntity) {
        if (!this.isInGround()) {
            super.push(xa, ya, za, pushingEntity);
        }
    }
    // Paper end - add push source entity param

    @Redirect(method = "onHitEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;discard()V"))
    private void fission$discardHit(AbstractArrow instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
    }

    @ModifyVariable(method = "onHitEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getRemainingFireTicks()I"))
    private DamageSource fission$critical(DamageSource damageSource) {
        if (this.isCritArrow()) damageSource = damageSource.critical(); // Paper - add critical damage API
        return damageSource;
    }

    @WrapOperation(method = "onHitEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;igniteForSeconds(F)V"))
    private void fission$combustByEntity(Entity entity, float seconds, Operation<Void> original) {
        // CraftBukkit start
        org.bukkit.event.entity.EntityCombustByEntityEvent combustEvent = new org.bukkit.event.entity.EntityCombustByEntityEvent(this.getBukkitEntity(), entity.getBukkitEntity(), seconds);
        if (combustEvent.callEvent()) {
            entity.igniteForSeconds(combustEvent.getDuration(), false);
        }
        // CraftBukkit end
    }

    @Redirect(method = "doKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;push(DDD)V"))
    private void fission$knockbackPush(LivingEntity mob, double xa, double ya, double za) {
        mob.push(xa, ya, za, this); // Paper - Add EntityKnockbackByEntityEvent and EntityPushedByEntityAttackEvent
    }

    @ModifyConstant(method = "readAdditionalSaveData", constant = @Constant(doubleValue = 2.0))
    private double fission$tridentDamage(double original) {
        return (Object) this instanceof ThrownTrident ? net.minecraft.world.item.TridentItem.BASE_DAMAGE : original; // Paper - Allow trident custom damage
    }

    // Paper start - Fix PickupStatus getting reset
    public void setOwner(final @Nullable Entity owner, final boolean resetPickup) {
        // Paper end - Fix PickupStatus getting reset
        // FissionMC: vanilla setOwner(Entity) is super.setOwner + the pickup reset; restore pickup when not resetting
        final AbstractArrow.Pickup previous = this.pickup;
        ((AbstractArrow) (Object) this).setOwner(owner);
        if (!resetPickup) this.pickup = previous; // Paper - Fix PickupStatus getting reset
    }

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void fission$playerTouch(Player player, CallbackInfo ci) {
        ci.cancel();
        if (!this.level().isClientSide() && (this.isInGround() || this.isNoPhysics()) && this.shakeTime <= 0) {
            // CraftBukkit start
            ItemStack pickupItem = this.getPickupItem();
            if (this.pickup == AbstractArrow.Pickup.ALLOWED && !pickupItem.isEmpty() && player.getInventory().canHold(pickupItem) > 0) {
                net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(this.level(), this.getX(), this.getY(), this.getZ(), pickupItem);
                org.bukkit.event.player.PlayerPickupArrowEvent event = new org.bukkit.event.player.PlayerPickupArrowEvent((org.bukkit.entity.Player) player.getBukkitEntity(), (org.bukkit.entity.Item) item.getBukkitEntity(), (org.bukkit.entity.AbstractArrow) this.getBukkitEntity());
                // event.setCancelled(!player.canPickUpLoot()); TODO
                if (!event.callEvent()) {
                    return;
                }
                pickupItem = item.getItem();
            }
            final boolean pickedUp;
            net.fissionmc.backend.entitytypes.PickupItem.CURRENT.set(pickupItem); // Paper - pass custom pickup item
            try {
                pickedUp = this.tryPickup(player);
            } finally {
                net.fissionmc.backend.entitytypes.PickupItem.CURRENT.remove();
            }
            if (pickedUp) {
                // CraftBukkit end
                player.take(this, 1);
                this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.PICKUP); // CraftBukkit - add Bukkit remove cause
            }
        }
    }

    @Redirect(method = "tryPickup", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;getPickupItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack fission$customPickupItem(AbstractArrow instance) {
        return net.fissionmc.backend.entitytypes.PickupItem.orElse(this.getPickupItem()); // Paper - pass custom pickup item
    }
}
