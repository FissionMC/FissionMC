package net.fissionmc.backend.mixin.world.entity.animal.allay;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.allay.Allay;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/animal/allay/Allay.java.patch */
@Mixin(Allay.class)
public abstract class AllayMixin {
    @Shadow @Final private static EntityDataAccessor<Boolean> DATA_CAN_DUPLICATE;

    // Surface: public (see surface/members/entitytypes.txt); the loader widens it.
    private boolean forceDancing = false; // CraftBukkit
    // TODO(behaviour): forceDancing consulted in tick/shouldStopDancing; heal(REGEN); DuplicationCooldown loaded as long;
    //  vanilla duplicateAllay() should spawn with SpawnReason.DUPLICATION.

    // CraftBukkit start
    public void setCanDuplicate(boolean canDuplicate) {
        ((Allay) (Object) this).getEntityData().set(AllayMixin.DATA_CAN_DUPLICATE, canDuplicate);
    }
    // CraftBukkit end

    // FissionMC: Paper's `Allay duplicateAllay()` (return type changed from void), added under another name.
    public @Nullable Allay duplicateAllayReturning() { // CraftBukkit - return allay
        Allay self = (Allay) (Object) this;
        Allay allay = EntityTypes.ALLAY.create(self.level(), EntitySpawnReason.BREEDING);
        if (allay != null) {
            allay.snapTo(self.position());
            allay.setPersistenceRequired();
            allay.resetDuplicationCooldown();
            self.resetDuplicationCooldown();
            self.level().addFreshEntity(allay, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DUPLICATION); // CraftBukkit - reason for duplicated allay
        }

        return allay; // CraftBukkit
    }
}
