package net.fissionmc.backend.mixin.world.entity.vehicle;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/vehicle/ContainerEntity.java.patch */
@Mixin(ContainerEntity.class)
public interface ContainerEntityMixin {
    // FissionMC: the in-method hunks of this patch are applied at the call sites in AbstractChestBoatMixin and
    //  AbstractMinecartContainerMixin (see net.fissionmc.backend.entitytypes.ContainerEntityPaper).

    // Paper start - LootTable API
    default com.destroystokyo.paper.loottable.PaperLootableInventoryData lootableData() {
        throw new UnsupportedOperationException("Implement this method");
    }

    default com.destroystokyo.paper.loottable.PaperLootableInventory getLootableInventory() {
        return ((com.destroystokyo.paper.loottable.PaperLootableInventory) ((Entity) this).getBukkitEntity());
    }
    // Paper end - LootTable API
}
