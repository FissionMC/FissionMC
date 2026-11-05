package net.fissionmc.backend.mixin.world.entity;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PostSpawnProcessor;
import net.minecraft.world.item.ItemStack;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: paper-server/patches/sources/net/minecraft/world/entity/EntityType.java.patch
 * FissionMC: the extra {@code CreatureSpawnEvent.SpawnReason} argument is passed to vanilla's spawn through a ThreadLocal.
 * left: create(ValueInput, Level, request, generation) overload, SPIGOT-5665 try/catch on spawn egg NBT, filtered NBT paths, invalid-id debug logging.
 */
@Mixin(EntityType.class)
public abstract class EntityTypeMixin<T extends Entity> {
    private static final ThreadLocal<CreatureSpawnEvent.SpawnReason> FISSION$SPAWN_REASON = new ThreadLocal<>();

    private T fission$spawnWith(final CreatureSpawnEvent.SpawnReason reason, final java.util.function.Supplier<T> spawn) {
        final CreatureSpawnEvent.SpawnReason prev = FISSION$SPAWN_REASON.get();
        FISSION$SPAWN_REASON.set(reason);
        try {
            return spawn.get();
        } finally {
            FISSION$SPAWN_REASON.set(prev);
        }
    }

    // CraftBukkit start
    public @Nullable T spawn(final ServerLevel level, final @Nullable ItemStack itemStack, final @Nullable LivingEntity user, final BlockPos spawnPos, final EntitySpawnReason spawnReason, final boolean tryMoveDown, final boolean movedUp, final CreatureSpawnEvent.SpawnReason createSpawnReason) {
        final EntityType<T> self = (EntityType<T>) (Object) this;
        return this.fission$spawnWith(createSpawnReason, () -> self.spawn(level, itemStack, user, spawnPos, spawnReason, tryMoveDown, movedUp));
    }

    public @Nullable T spawn(final ServerLevel level, final BlockPos spawnPos, final EntitySpawnReason spawnReason, final CreatureSpawnEvent.SpawnReason creatureSpawnReason) {
        final EntityType<T> self = (EntityType<T>) (Object) this;
        return this.fission$spawnWith(creatureSpawnReason, () -> self.spawn(level, null, spawnPos, spawnReason, false, false));
    }

    public @Nullable T spawn(final ServerLevel level, final @Nullable PostSpawnProcessor<T> postSpawnConfig, final BlockPos spawnPos, final EntitySpawnReason spawnReason, final boolean tryMoveDown, final boolean movedUp, final CreatureSpawnEvent.SpawnReason creatureSpawnReason) {
        final EntityType<T> self = (EntityType<T>) (Object) this;
        return this.fission$spawnWith(creatureSpawnReason, () -> self.spawn(level, postSpawnConfig, spawnPos, spawnReason, tryMoveDown, movedUp));
    }
    // CraftBukkit end

    // vanilla spawn egg entry point: reason depends on the spawn reason (Paper - use correct spawn reason)
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "spawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntitySpawnReason;ZZ)Lnet/minecraft/world/entity/Entity;")
    private @Nullable T fission$spawnEggReason(ServerLevel level, @Nullable ItemStack itemStack, @Nullable LivingEntity user, BlockPos spawnPos, EntitySpawnReason spawnReason, boolean tryMoveDown, boolean movedUp, Operation<T> original) {
        if (FISSION$SPAWN_REASON.get() != null) {
            return original.call(level, itemStack, user, spawnPos, spawnReason, tryMoveDown, movedUp);
        }
        return this.fission$spawnWith(spawnReason == EntitySpawnReason.DISPENSER ? CreatureSpawnEvent.SpawnReason.DISPENSE_EGG : CreatureSpawnEvent.SpawnReason.SPAWNER_EGG, () -> original.call(level, itemStack, user, spawnPos, spawnReason, tryMoveDown, movedUp));
    }

    @Inject(method = "spawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/PostSpawnProcessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntitySpawnReason;ZZ)Lnet/minecraft/world/entity/Entity;", at = @At("HEAD"), cancellable = true)
    private void fission$preCreatureSpawn(ServerLevel level, @Nullable PostSpawnProcessor<T> postSpawnConfig, BlockPos spawnPos, EntitySpawnReason spawnReason, boolean tryMoveDown, boolean movedUp, CallbackInfoReturnable<T> cir) {
        // Paper start - PreCreatureSpawnEvent
        final CreatureSpawnEvent.SpawnReason creatureSpawnReason = this.fission$currentReason();
        com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent event = new com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent(
            org.bukkit.craftbukkit.util.CraftLocation.toBukkit(spawnPos, level),
            org.bukkit.craftbukkit.entity.CraftEntityType.minecraftToBukkit((EntityType<?>) (Object) this),
            creatureSpawnReason
        );
        if (!event.callEvent()) {
            cir.setReturnValue(null);
        }
        // Paper end - PreCreatureSpawnEvent
    }

    private CreatureSpawnEvent.SpawnReason fission$currentReason() {
        final CreatureSpawnEvent.SpawnReason reason = FISSION$SPAWN_REASON.get();
        return reason != null ? reason : CreatureSpawnEvent.SpawnReason.DEFAULT;
    }

    @WrapOperation(method = "spawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/PostSpawnProcessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntitySpawnReason;ZZ)Lnet/minecraft/world/entity/Entity;", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"))
    private void fission$addWithReason(ServerLevel level, Entity entity, Operation<Void> original) {
        level.addFreshEntityWithPassengers(entity, this.fission$currentReason()); // CraftBukkit
    }

    @ModifyReturnValue(method = "spawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/PostSpawnProcessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntitySpawnReason;ZZ)Lnet/minecraft/world/entity/Entity;", at = @At("RETURN"))
    private T fission$removedByEvent(T entity) {
        return entity != null && entity.isRemoved() ? null : entity; // Don't return an entity when CreatureSpawnEvent is canceled
    }
}
