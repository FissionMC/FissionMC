package net.fissionmc.backend.mixin.world.entity.vehicle.minecart;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/vehicle/minecart/AbstractMinecartContainer.java.patch */
@Mixin(AbstractMinecartContainer.class)
public abstract class AbstractMinecartContainerMixin {
    private final com.destroystokyo.paper.loottable.PaperLootableInventoryData lootableData = new com.destroystokyo.paper.loottable.PaperLootableInventoryData(); // Paper - LootTable API

    // Paper start - LootTable API
    public com.destroystokyo.paper.loottable.PaperLootableInventoryData lootableData() {
        return this.lootableData;
    }
    // Paper end - LootTable API
    // TODO(members, Container owner): CraftBukkit Container impls (transaction, maxStack, getContents, onOpen, onClose,
    //  getViewers, getOwner, getMaxStackSize, setMaxStackSize, getLocation) belong with the Container surface.
    // TODO(behaviour): itemStacks sized by getContainerSize() (SPIGOT-3513); remove(reason, cause) override.
}
