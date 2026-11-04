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

/**
 * Members Paper adds to {@link Mob} (paper-server/patches/sources/net/minecraft/world/entity/Mob.java.patch).
 */
@Mixin(Mob.class)
public abstract class MobMixin extends LivingEntity {
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
}
