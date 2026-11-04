package net.fissionmc.backend.mixin.world.entity.player;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/player/Inventory.java.patch */
@Mixin(Inventory.class)
public abstract class InventoryMixin {
    @Shadow @Final public static it.unimi.dsi.fastutil.ints.Int2ObjectMap<EquipmentSlot> EQUIPMENT_SLOT_MAPPING;
    @Shadow @Final private NonNullList<ItemStack> items;
    @Shadow @Final public Player player;
    @Shadow @Final private EntityEquipment equipment;
    @Shadow private int selected;

    @Shadow public abstract ItemStack getItem(int slot);
    @Shadow public abstract void setSelectedSlot(int slot);
    @Shadow public abstract int getFreeSlot();
    @Shadow private boolean hasRemainingSpaceForItem(final ItemStack slotItemStack, final ItemStack newItemStack) { throw new AssertionError(); }

    // Paper start - add fields and methods
    // Surface: public static final / public. Mixin requires private fields; the loader widens them.
    private static final EquipmentSlot[] EQUIPMENT_SLOTS_SORTED_BY_INDEX = EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet()
        .stream()
        .sorted(java.util.Comparator.comparingInt(it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry::getIntKey))
        .map(java.util.Map.Entry::getValue).toArray(EquipmentSlot[]::new);
    private java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = net.minecraft.world.item.Item.ABSOLUTE_MAX_STACK_SIZE; // FissionMC: Paper's Container.MAX_STACK

    public java.util.List<ItemStack> getContents() {
        java.util.List<ItemStack> combined = new java.util.ArrayList<>(this.items.size() + EQUIPMENT_SLOT_MAPPING.size());
        combined.addAll(this.items);
        for (EquipmentSlot equipmentSlot : EQUIPMENT_SLOTS_SORTED_BY_INDEX) {
            ItemStack itemStack = this.equipment.get(equipmentSlot);
            combined.add(itemStack); // Include empty items
        };
        return combined;
    }

    public java.util.List<ItemStack> getArmorContents() {
        java.util.List<ItemStack> items = new java.util.ArrayList<>(4);
        for (EquipmentSlot equipmentSlot : EQUIPMENT_SLOTS_SORTED_BY_INDEX) {
            if (equipmentSlot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                items.add(this.equipment.get(equipmentSlot));
            }
        }
        return items;
    }

    public java.util.List<ItemStack> getExtraContent() {
        java.util.List<ItemStack> items = new java.util.ArrayList<>();
        for (EquipmentSlot equipmentSlot : EQUIPMENT_SLOTS_SORTED_BY_INDEX) {
            if (equipmentSlot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) { // Non humanoid armor is considered extra
                items.add(this.equipment.get(equipmentSlot));
            }
        }
        return items;
    }

    public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.add(player);
    }

    public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.remove(player);
    }

    public java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        return this.transaction;
    }

    public org.bukkit.inventory.InventoryHolder getOwner() {
        return this.player.getBukkitEntity();
    }

    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    public org.bukkit.Location getLocation() {
        return this.player.getBukkitEntity().getLocation();
    }
    // Paper end - add fields and methods

    // TODO(behaviour): Paper reorders hasRemainingSpaceForItem (check stackable before components)

    // CraftBukkit start - Watch method above! :D
    public int canHold(ItemStack itemStack) {
        int remains = itemStack.getCount();
        for (int slot = 0; slot < this.items.size(); ++slot) {
            ItemStack itemInSlot = this.getItem(slot);
            if (itemInSlot.isEmpty()) {
                return itemStack.getCount();
            }

            if (this.hasRemainingSpaceForItem(itemInSlot, itemStack)) {
                remains -= (itemInSlot.getMaxStackSize() < this.getMaxStackSize() ? itemInSlot.getMaxStackSize() : this.getMaxStackSize()) - itemInSlot.getCount();
            }
            if (remains <= 0) {
                return itemStack.getCount();
            }
        }

        ItemStack itemInOffhand = this.equipment.get(EquipmentSlot.OFFHAND);
        if (this.hasRemainingSpaceForItem(itemInOffhand, itemStack)) {
            remains -= (itemInOffhand.getMaxStackSize() < this.getMaxStackSize() ? itemInOffhand.getMaxStackSize() : this.getMaxStackSize()) - itemInOffhand.getCount();
        }
        if (remains <= 0) {
            return itemStack.getCount();
        }

        return itemStack.getCount() - remains;
    }
    // CraftBukkit end

    // Paper start - Add PlayerPickItemEvent
    // TODO(behaviour): Paper replaces addAndPickItem(ItemStack)/pickSlot(int) with these overloads (callers pass the target slot)
    public void addAndPickItem(final ItemStack itemStack, final int targetSlot) {
        this.setSelectedSlot(targetSlot);
        // Paper end - Add PlayerPickItemEvent
        if (!this.items.get(this.selected).isEmpty()) {
            int freeSlot = this.getFreeSlot();
            if (freeSlot != Inventory.NOT_FOUND_INDEX) {
                this.items.set(freeSlot, this.items.get(this.selected));
            }
        }

        this.items.set(this.selected, itemStack);
    }

    public void pickSlot(final int slot, final int targetSlot) {
        this.setSelectedSlot(targetSlot);
    // Paper end - Add PlayerPickItemEvent
        ItemStack tmp = this.items.get(this.selected);
        this.items.set(this.selected, this.items.get(slot));
        this.items.set(slot, tmp);
    }
}
