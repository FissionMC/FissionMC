package net.fissionmc.backend.mixin.world.entity;

import java.util.Objects;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/**
 * Members Paper adds to {@link Mob} (paper-server/patches/sources/net/minecraft/world/entity/Mob.java.patch).
 */
@Mixin(Mob.class)
public abstract class MobMixin extends LivingEntity {
    @Shadow public abstract net.minecraft.world.entity.ai.control.JumpControl getJumpControl();
    @Shadow private @Nullable LivingEntity target;

    @Shadow public abstract @Nullable LivingEntity getTargetUnchecked();
    @Shadow protected abstract @Nullable LivingEntity asValidTarget(@Nullable LivingEntity target);
    @Shadow public abstract ItemStack equipItemIfPossible(ServerLevel level, ItemStack itemStack);
    @Shadow public abstract <T extends Mob> @Nullable T convertTo(EntityType<T> entityType, ConversionParams params, EntitySpawnReason spawnReason, ConversionParams.AfterConversion<T> afterConversion);

    protected MobMixin(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    private net.minecraft.world.entity.ai.goal.@Nullable FloatGoal goalFloat; // Paper - Allow nerfed mobs to jump and float // TODO(behaviour): set by subclasses' registerGoals, used in serverAiStep when !aware
    private boolean aware = true; // CraftBukkit // TODO(behaviour): serverAiStep skips AI when !aware; saved as Bukkit.Aware
    private net.kyori.adventure.util.TriState despawnInPeacefulOverride = net.kyori.adventure.util.TriState.NOT_SET; // Paper - allow changing despawnInPeaceful // TODO(behaviour): checkDespawn + save/load

    // TODO(behaviour): vanilla setTarget(target) should delegate here with TargetReason.UNKNOWN
    public boolean setTarget(@Nullable LivingEntity target, EntityTargetEvent.@Nullable TargetReason reason) {
        Mob self = (Mob) (Object) this;
        LivingEntity currentTarget = this.getTargetUnchecked();
        if (Objects.equals(currentTarget, target)) {
            return false;
        }
        LivingEntity originalTarget = target;
        target = asValidTarget(target);
        if (reason != null) {
            if (target != originalTarget) { // target got pruned in asValidTarget
                reason = org.bukkit.craftbukkit.event.CraftEventFactory.getForgotTargetReason(self, currentTarget, true);
                originalTarget = target;
            } else if (reason == EntityTargetEvent.TargetReason.FORGOT_TARGET || (reason == EntityTargetEvent.TargetReason.UNKNOWN && target == null)) { // try to get a more relevant reason
                reason = org.bukkit.craftbukkit.event.CraftEventFactory.getForgotTargetReason(self, currentTarget, false);
            }
            if (reason == EntityTargetEvent.TargetReason.UNKNOWN) {
                this.level().getCraftServer().getLogger().log(java.util.logging.Level.WARNING, "Unknown target reason, please report on the issue tracker", new Exception());
            }
            org.bukkit.event.entity.EntityTargetLivingEntityEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityTargetLivingEvent(self, target, reason);
            if (event.isCancelled()) {
                return false;
            }

            if (event.getTarget() != null) {
                target = ((org.bukkit.craftbukkit.entity.CraftLivingEntity) event.getTarget()).getHandle();
                if (target != originalTarget) {
                    target = asValidTarget(target);
                }
            } else {
                target = null;
            }
        }
        this.target = target;
        return true;
        // CraftBukkit end
    }

    // FissionMC: the item entity of the in-progress equipItemIfPossible(level, stack, entity) call
    @Unique private @Nullable ItemEntity fission$equipItemEntity;

    // TODO(behaviour): vanilla equipItemIfPossible must call CraftEventFactory.callEntityPickupItemEvent when fission$equipItemEntity != null;
    // pickUpItem should pass its ItemEntity
    public ItemStack equipItemIfPossible(final ServerLevel level, final ItemStack itemStack, final @Nullable ItemEntity entity) {
        final ItemEntity prev = this.fission$equipItemEntity;
        this.fission$equipItemEntity = entity;
        try {
            return this.equipItemIfPossible(level, itemStack);
        } finally {
            this.fission$equipItemEntity = prev;
        }
    }

    // Paper start
    private Set<EquipmentSlot> droppedEquipmentSlots = new java.util.HashSet<>(); // TODO(behaviour): dropCustomDeathLoot/dropPreservedEquipment hunks
    protected boolean guaranteedToDropUndamaged(EquipmentSlot slot) {
        return false;
    }
    // Paper end

    // FissionMC: reasons of the in-progress convertTo(..., transformReason, creatureSpawnReason) call
    @Unique private EntityTransformEvent.@Nullable TransformReason fission$transformReason = EntityTransformEvent.TransformReason.UNKNOWN;
    @Unique private CreatureSpawnEvent.@Nullable SpawnReason fission$creatureSpawnReason = CreatureSpawnEvent.SpawnReason.DEFAULT;

    // TODO(behaviour): vanilla convertTo must fire EntityTransformEvent with fission$transformReason (skip when null), add the new mob with
    // fission$creatureSpawnReason and discard with Cause.TRANSFORMATION (Paper - entity zap event)
    public <T extends Mob> @Nullable T convertTo(
        EntityType<T> entityType, ConversionParams params, EntitySpawnReason spawnReason, ConversionParams.AfterConversion<T> afterConversion, EntityTransformEvent.@Nullable TransformReason transformReason, CreatureSpawnEvent.@Nullable SpawnReason creatureSpawnReason
    ) {
        final EntityTransformEvent.TransformReason prevTransform = this.fission$transformReason;
        final CreatureSpawnEvent.SpawnReason prevSpawn = this.fission$creatureSpawnReason;
        this.fission$transformReason = transformReason;
        this.fission$creatureSpawnReason = creatureSpawnReason;
        try {
            return this.convertTo(entityType, params, spawnReason, afterConversion);
        } finally {
            this.fission$transformReason = prevTransform;
            this.fission$creatureSpawnReason = prevSpawn;
        }
    }

    public <T extends Mob> @Nullable T convertTo(EntityType<T> entityType, ConversionParams params, ConversionParams.AfterConversion<T> afterConversion, EntityTransformEvent.@Nullable TransformReason transformReason, CreatureSpawnEvent.@Nullable SpawnReason creatureSpawnReason) {
        return this.convertTo(entityType, params, EntitySpawnReason.CONVERSION, afterConversion, transformReason, creatureSpawnReason);
    }

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void fission$setTargetUnknown(@Nullable LivingEntity target, CallbackInfo ci) {
        // CraftBukkit start - fire event
        this.setTarget(target, EntityTargetEvent.TargetReason.UNKNOWN);
        ci.cancel();
    }

    @WrapOperation(method = "pickUpItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;equipItemIfPossible(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack fission$pickUpWithEntity(Mob self, ServerLevel level, ItemStack stack, Operation<ItemStack> original, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) ItemEntity entity) {
        return this.equipItemIfPossible(level, stack, entity); // CraftBukkit - add item
    }

    @WrapOperation(method = "pickUpItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V"))
    private void fission$pickUpDiscard(ItemEntity entity, Operation<Void> original) {
        entity.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.PICKUP); // CraftBukkit - add Bukkit remove cause
    }

    // CraftBukkit start
    @ModifyExpressionValue(method = "equipItemIfPossible(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;canHoldItem(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean fission$pickupEvent(boolean canPickup) {
        // TODO(behaviour): Paper also fires the event when canReplace is false (event pre-cancelled); here only when canReplace was true
        if (this.fission$equipItemEntity != null) {
            canPickup = !org.bukkit.craftbukkit.event.CraftEventFactory.callEntityPickupItemEvent((Mob) (Object) this, this.fission$equipItemEntity, 0, !canPickup).isCancelled();
        }
        return canPickup;
    }
    // CraftBukkit end

    @WrapOperation(method = "checkDespawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;discard()V"))
    private void fission$despawnCause(Mob self, Operation<Void> original) {
        self.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
    }

    @ModifyExpressionValue(method = "checkDespawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityType;isAllowedInPeaceful()Z"))
    private boolean fission$despawnInPeaceful(boolean allowed) {
        return !this.despawnInPeacefulOverride.toBooleanOrElse(!allowed); // Paper - allow changing despawnInPeaceful
    }

    @WrapOperation(method = "checkDespawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getNearestPlayer(Lnet/minecraft/world/entity/Entity;D)Lnet/minecraft/world/entity/player/Player;"))
    private net.minecraft.world.entity.player.Player fission$nearestAffectingSpawning(Level level, net.minecraft.world.entity.Entity entity, double distance, Operation<net.minecraft.world.entity.player.Player> original) {
        // Paper - Affects Spawning API
        return level.getNearestPlayer(entity.getX(), entity.getY(), entity.getZ(), distance, (java.util.function.Predicate<net.minecraft.world.entity.Entity>) e -> e instanceof net.minecraft.world.entity.player.Player p && p.affectsSpawning && !p.isSpectator());
    }
    // TODO(behaviour): checkDespawn configurable despawn ranges (paperConfig entities.spawning.despawn-ranges; defaults equal vanilla)

    // Paper start - Allow nerfed mobs to jump and float
    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void fission$notAware(CallbackInfo ci) {
        if (!this.aware) {
            if (this.goalFloat != null) {
                if (this.goalFloat.canUse()) this.goalFloat.tick();
                this.getJumpControl().tick();
            }
            ci.cancel();
        }
    }
    // Paper end - Allow nerfed mobs to jump and float

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void fission$saveAware(net.minecraft.world.level.storage.ValueOutput output, CallbackInfo ci) {
        output.putBoolean("Bukkit.Aware", this.aware); // CraftBukkit
        // Paper start - allow changing despawnInPeaceful
        if (this.despawnInPeacefulOverride != net.kyori.adventure.util.TriState.NOT_SET) {
            output.putString("Paper.DespawnInPeacefulOverride", this.despawnInPeacefulOverride.name());
        }
        // Paper end - allow changing despawnInPeaceful
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void fission$loadAware(net.minecraft.world.level.storage.ValueInput input, CallbackInfo ci) {
        this.aware = input.getBooleanOr("Bukkit.Aware", true); // CraftBukkit
        this.despawnInPeacefulOverride = input.read("Paper.DespawnInPeacefulOverride", io.papermc.paper.util.PaperCodecs.TRI_STATE_CODEC).orElse(net.kyori.adventure.util.TriState.NOT_SET); // Paper - allow changing despawnInPeaceful
    }

    @ModifyReturnValue(method = "canShearEquipment", at = @At("RETURN"))
    private boolean fission$noShearVillager(boolean can) {
        return can && !((Object) this instanceof net.minecraft.world.entity.npc.villager.Villager); // Paper - Prevent shearing villager display items
    }

    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;hasPickUpDelay()Z"))
    private boolean fission$canMobPickup(ItemEntity entity, Operation<Boolean> original) {
        return original.call(entity) || !entity.canMobPickup; // Paper - Item#canEntityPickup (skips the pickup like `continue`)
    }
}
