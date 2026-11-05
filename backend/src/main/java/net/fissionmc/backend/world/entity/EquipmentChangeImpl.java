package net.fissionmc.backend.world.entity;

// Paper: LivingEntity#collectEquipmentChanges local record (EntityEquipmentChangedEvent)
public record EquipmentChangeImpl(org.bukkit.inventory.ItemStack oldItem, org.bukkit.inventory.ItemStack newItem) implements io.papermc.paper.event.entity.EntityEquipmentChangedEvent.EquipmentChange {
    @Override
    public org.bukkit.inventory.ItemStack oldItem() {
        return this.oldItem.clone();
    }

    @Override
    public org.bukkit.inventory.ItemStack newItem() {
        return this.newItem.clone();
    }
}
