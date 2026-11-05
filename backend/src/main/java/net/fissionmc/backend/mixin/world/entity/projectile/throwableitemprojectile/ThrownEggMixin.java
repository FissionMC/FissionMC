package net.fissionmc.backend.mixin.world.entity.projectile.throwableitemprojectile;

import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEgg.java.patch */
@Mixin(ThrownEgg.class)
public abstract class ThrownEggMixin extends ThrowableItemProjectile {
    @Shadow @Final private static EntityDimensions ZERO_SIZED_DIMENSIONS;

    protected ThrownEggMixin(final EntityType<? extends ThrowableItemProjectile> type, final Level level) {
        super(type, level);
    }

    // FissionMC: replaced body (PlayerEggThrowEvent, ThrownEggHatchEvent, custom hatching type, EGG spawn reason)
    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void fission$onHit(HitResult hitResult, CallbackInfo ci) {
        ci.cancel();
        super.onHit(hitResult);
        if (!this.level().isClientSide()) {
            // CraftBukkit start
            boolean hatching = this.random.nextInt(8) == 0;
            if (true) {
                // CraftBukkit end
                int count = 1;
                if (this.random.nextInt(32) == 0) {
                    count = 4;
                }
                // CraftBukkit start
                if (!hatching) {
                    count = 0;
                }

                net.minecraft.world.entity.Entity shooter = this.getOwner();
                org.bukkit.entity.EntityType hatchingType = org.bukkit.entity.EntityType.CHICKEN;
                if (shooter instanceof net.minecraft.server.level.ServerPlayer) {
                    org.bukkit.event.player.PlayerEggThrowEvent event = new org.bukkit.event.player.PlayerEggThrowEvent((org.bukkit.entity.Player) shooter.getBukkitEntity(), (org.bukkit.entity.Egg) this.getBukkitEntity(), hatching, (byte) count, hatchingType);
                    event.callEvent();

                    hatching = event.isHatching();
                    count = hatching ? event.getNumHatches() : 0; // If hatching is set to false, ensure child count is 0
                    hatchingType = event.getHatchingType();
                }
                // CraftBukkit end
                // Paper start - Add ThrownEggHatchEvent
                com.destroystokyo.paper.event.entity.ThrownEggHatchEvent event = new com.destroystokyo.paper.event.entity.ThrownEggHatchEvent((org.bukkit.entity.Egg) this.getBukkitEntity(), hatching, (byte) count, hatchingType);
                event.callEvent();
                hatching = event.isHatching();
                count = hatching ? event.getNumHatches() : 0; // If hatching is set to false, ensure child count is 0
                hatchingType = event.getHatchingType();
                EntityType<?> newEntityType = org.bukkit.craftbukkit.entity.CraftEntityType.bukkitToMinecraft(hatchingType);
                // Paper end - Add ThrownEggHatchEvent

                for (int i = 0; i < count; i++) {
                    net.minecraft.world.entity.Entity chicken = newEntityType.create(this.level(), net.minecraft.world.entity.EntitySpawnReason.TRIGGERED); // CraftBukkit
                    if (chicken != null) {
                        // CraftBukkit start
                        if (chicken.getBukkitEntity() instanceof org.bukkit.entity.Ageable ageable) {
                            ageable.setBaby();
                        }
                        // CraftBukkit end
                        chicken.snapTo(this.getX(), this.getY(), this.getZ(), this.getYRot(), 0.0F);
                        // CraftBukkit start
                        if (chicken instanceof Chicken realChicken) {
                            Optional.ofNullable(this.getItem().get(DataComponents.CHICKEN_VARIANT)).ifPresent(realChicken::setVariant);
                        }
                        // CraftBukkit end
                        if (!chicken.fudgePositionAfterSizeChange(ZERO_SIZED_DIMENSIONS)) {
                            break;
                        }

                        this.level().addFreshEntity(chicken, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.EGG); // CraftBukkit
                    }
                }
            }

            this.level().broadcastEntityEvent(this, EntityEvent.DEATH);
            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
        }
    }
}
