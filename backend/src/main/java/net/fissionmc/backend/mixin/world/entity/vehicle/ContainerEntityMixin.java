package net.fissionmc.backend.mixin.world.entity.vehicle;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/vehicle/ContainerEntity.java.patch */
@Mixin(ContainerEntity.class)
public interface ContainerEntityMixin {
    // TODO(behaviour): add/readChestVehicleSaveData save lootableData and always save items;
    //  unpackChestVehicleLootTable uses lootableData().shouldReplenish/shouldClearLootTable; interactWithContainerVehicle cancel handling.

    // Paper start - LootTable API
    default com.destroystokyo.paper.loottable.PaperLootableInventoryData lootableData() {
        throw new UnsupportedOperationException("Implement this method");
    }

    default com.destroystokyo.paper.loottable.PaperLootableInventory getLootableInventory() {
        return ((com.destroystokyo.paper.loottable.PaperLootableInventory) ((Entity) this).getBukkitEntity());
    }
    // Paper end - LootTable API
}
