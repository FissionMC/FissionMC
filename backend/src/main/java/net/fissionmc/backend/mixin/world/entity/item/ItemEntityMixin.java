package net.fissionmc.backend.mixin.world.entity.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: paper-server/patches/sources/net/minecraft/world/entity/item/ItemEntity.java.patch
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin extends Entity {
    @Shadow private int age;
    @Shadow private int pickupDelay;
    @Shadow private @org.jspecify.annotations.Nullable UUID target;
    @Shadow public abstract ItemStack getItem();

    protected ItemEntityMixin(final EntityType<?> type, final Level level) {
        super(type, level);
    }

    private boolean canMobPickup = true; // Paper - Item#canEntityPickup (checked in MobMixin aiStep pickup loop)
    private int despawnRate = -1; // Paper - Alternative item-despawn-rate
    private net.kyori.adventure.util.TriState frictionState = net.kyori.adventure.util.TriState.NOT_SET; // Paper - Friction API
    // TODO(behaviour): Friction API in tick() (ground friction 1.0 when FALSE) and Paper.FrictionState save/load; fixItemsMergingThroughWalls (config, default off)

    @Inject(method = "setItem", at = @At("TAIL"))
    private void fission$despawnRate(ItemStack itemStack, CallbackInfo ci) {
        this.despawnRate = this.level().paperConfig().entities.spawning.altItemDespawnRate.enabled ? this.level().paperConfig().entities.spawning.altItemDespawnRate.items.getOrDefault(itemStack.getItem(), this.level().spigotConfig.itemDespawnRate) : this.level().spigotConfig.itemDespawnRate; // Paper - Alternative item-despawn-rate
    }

    @Inject(method = "makeFakeItem", at = @At("TAIL"))
    private void fission$fakeItemAge(CallbackInfo ci) {
        this.age = this.despawnRate - 1; // Spigot // Paper - Alternative item-despawn-rate
    }

    @ModifyConstant(method = {"tick", "isMergable"}, constant = @Constant(intValue = 6000))
    private int fission$despawnRateConstant(int vanilla) {
        return this.despawnRate; // Spigot // Paper - Alternative item-despawn-rate
    }

    // tick(): ordinal 0 = item empty -> discard, ordinal 1 = age >= despawnRate
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V", ordinal = 0))
    private void fission$tickDiscardEmpty(ItemEntity self, Operation<Void> original) {
        self.discard(EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V", ordinal = 1))
    private void fission$tickDespawn(ItemEntity self, Operation<Void> original) {
        // CraftBukkit start - fire ItemDespawnEvent
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callItemDespawnEvent(self).isCancelled()) {
            this.age = 0;
            return;
        }
        // CraftBukkit end
        self.discard(EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
    }

    @WrapOperation(method = "mergeWithNeighbours", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;inflate(DDD)Lnet/minecraft/world/phys/AABB;"))
    private net.minecraft.world.phys.AABB fission$mergeRadius(net.minecraft.world.phys.AABB box, double x, double y, double z, Operation<net.minecraft.world.phys.AABB> original) {
        double radius = this.level().spigotConfig.itemMerge; // Spigot
        return original.call(box, radius, this.level().paperConfig().entities.behavior.onlyMergeItemsHorizontally ? 0 : radius - 0.5D, radius);
    }

    @Inject(method = "merge(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"), cancellable = true)
    private static void fission$mergeEvent(ItemEntity fromItem, ItemStack fromStack, ItemEntity toItem, ItemStack toStack, CallbackInfo ci) {
        // CraftBukkit start
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.callItemMergeEvent(fromItem, toItem)) {
            ci.cancel();
        }
        // CraftBukkit end
    }

    @WrapOperation(method = "merge(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V"))
    private static void fission$mergeDiscard(ItemEntity self, Operation<Void> original) {
        self.discard(EntityRemoveEvent.Cause.MERGE); // CraftBukkit - add Bukkit remove cause
    }

    @Inject(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;markHurt()V"), cancellable = true)
    private void fission$hurtEvent(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
        // CraftBukkit start
        if (org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(this, source, damage)) {
            cir.setReturnValue(false);
        }
        // CraftBukkit end
    }

    @WrapOperation(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V"))
    private void fission$hurtDiscard(ItemEntity self, Operation<Void> original) {
        self.discard(EntityRemoveEvent.Cause.DEATH); // CraftBukkit - add Bukkit remove cause
    }

    @WrapOperation(method = "readAdditionalSaveData", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V"))
    private void fission$readDiscard(ItemEntity self, Operation<Void> original) {
        self.discard(null); // CraftBukkit - add Bukkit remove cause
    }

    // replaced body: Paper fires PlayerAttemptPickupItemEvent / PlayerPickupItemEvent / EntityPickupItemEvent inside the body
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void fission$playerTouch(Player player, CallbackInfo ci) {
        ci.cancel();
        if (!this.level().isClientSide()) {
            ItemStack itemStack = this.getItem();
            Item item = itemStack.getItem();
            int orgCount = itemStack.getCount();
            // CraftBukkit start - fire PlayerPickupItemEvent
            int canHold = player.getInventory().canHold(itemStack);
            int remaining = orgCount - canHold;
            boolean flyAtPlayer = false; // Paper

            // Paper start - PlayerAttemptPickupItemEvent
            if (this.pickupDelay <= 0) {
                org.bukkit.event.player.PlayerAttemptPickupItemEvent attemptEvent = new org.bukkit.event.player.PlayerAttemptPickupItemEvent((org.bukkit.entity.Player) player.getBukkitEntity(), (org.bukkit.entity.Item) this.getBukkitEntity(), remaining);
                this.level().getCraftServer().getPluginManager().callEvent(attemptEvent);

                flyAtPlayer = attemptEvent.getFlyAtPlayer();
                if (attemptEvent.isCancelled()) {
                    if (flyAtPlayer) {
                        player.take(this, orgCount);
                    }

                    return;
                }
            }

            if (this.pickupDelay <= 0 && canHold > 0) {
                itemStack.setCount(canHold);
                // Call legacy event
                org.bukkit.event.player.PlayerPickupItemEvent playerEvent = new org.bukkit.event.player.PlayerPickupItemEvent((org.bukkit.entity.Player) player.getBukkitEntity(), (org.bukkit.entity.Item) this.getBukkitEntity(), remaining);
                playerEvent.setCancelled(!playerEvent.getPlayer().getCanPickupItems());
                this.level().getCraftServer().getPluginManager().callEvent(playerEvent);
                flyAtPlayer = playerEvent.getFlyAtPlayer(); // Paper
                if (playerEvent.isCancelled()) {
                    itemStack.setCount(orgCount); // SPIGOT-5294 - restore count
                    // Paper start
                    if (flyAtPlayer) {
                        player.take(this, orgCount);
                    }
                    // Paper end
                    return;
                }

                // Call newer event afterwards
                org.bukkit.event.entity.EntityPickupItemEvent entityEvent = new org.bukkit.event.entity.EntityPickupItemEvent(player.getBukkitEntity(), (org.bukkit.entity.Item) this.getBukkitEntity(), remaining);
                entityEvent.setCancelled(!entityEvent.getEntity().getCanPickupItems());
                this.level().getCraftServer().getPluginManager().callEvent(entityEvent);
                if (entityEvent.isCancelled()) {
                    itemStack.setCount(orgCount); // SPIGOT-5294 - restore count
                    return;
                }

                // Update the ItemStack if it was changed in the event
                ItemStack current = this.getItem();
                if (!itemStack.equals(current)) {
                    itemStack = current;
                } else {
                    itemStack.setCount(canHold + remaining); // = i
                }

                // Possibly < 0; fix here so we do not have to modify code below
                this.pickupDelay = 0;
            } else if (this.pickupDelay == 0) {
                // ensure that the code below isn't triggered if canHold says we can't pick the items up
                this.pickupDelay = -1;
            }
            // CraftBukkit end
            // Paper end - PlayerAttemptPickupItemEvent
            if (this.pickupDelay == 0 && (this.target == null || this.target.equals(player.getUUID())) && player.getInventory().add(itemStack)) {
                if (flyAtPlayer) // Paper - PlayerPickupItemEvent
                player.take(this, orgCount);
                if (itemStack.isEmpty()) {
                    this.discard(EntityRemoveEvent.Cause.PICKUP); // CraftBukkit - add Bukkit remove cause
                    itemStack.setCount(orgCount);
                }

                player.awardStat(Stats.ITEM_PICKED_UP.get(item), orgCount);
                player.onItemPickup((ItemEntity) (Object) this);
            }
        }
    }
}
