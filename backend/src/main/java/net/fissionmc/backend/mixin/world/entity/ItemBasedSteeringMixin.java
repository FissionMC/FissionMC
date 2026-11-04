package net.fissionmc.backend.mixin.world.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.ItemBasedSteering;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/ItemBasedSteering.java.patch */
@Mixin(ItemBasedSteering.class)
public abstract class ItemBasedSteeringMixin {
    @Shadow @Final private SynchedEntityData entityData;
    @Shadow @Final private EntityDataAccessor<Integer> boostTimeAccessor;
    @Shadow private boolean boosting;
    @Shadow private int boostTime;

    // CraftBukkit start
    public void boost(final int boostTime) {
        this.boosting = true;
        this.boostTime = 0;
        this.entityData.set(this.boostTimeAccessor, boostTime);
    }
    // CraftBukkit end
}
