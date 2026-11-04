package net.fissionmc.backend.mixin.world.entity.vehicle.boat;

import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/vehicle/boat/AbstractChestBoat.java.patch */
@Mixin(AbstractChestBoat.class)
public abstract class AbstractChestBoatMixin {
    // Paper start - LootTable API
    private final com.destroystokyo.paper.loottable.PaperLootableInventoryData lootableData = new com.destroystokyo.paper.loottable.PaperLootableInventoryData();

    public com.destroystokyo.paper.loottable.PaperLootableInventoryData lootableData() {
        return this.lootableData;
    }
    // Paper end - LootTable API
    // TODO(members, Container owner): CraftBukkit Container impls (transaction, maxStack, getContents, onOpen, onClose,
    //  getViewers, getOwner, getMaxStackSize, setMaxStackSize, getLocation) belong with the Container surface.
    // TODO(behaviour): remove(reason, cause) override; openCustomInventoryScreen cancel handling.
}
