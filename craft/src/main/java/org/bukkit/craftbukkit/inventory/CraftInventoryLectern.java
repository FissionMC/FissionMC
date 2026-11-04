package org.bukkit.craftbukkit.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import org.bukkit.block.Lectern;
import org.bukkit.inventory.LecternInventory;

public class CraftInventoryLectern extends CraftInventory implements LecternInventory {

    public MenuProvider provider;

    public CraftInventoryLectern(Container inventory) {
        super(inventory);
        if (inventory instanceof net.fissionmc.craft.compat.LecternInventory) { // FissionMC: Paper's LecternBlockEntity.LecternInventory is a compat interface
            this.provider = ((net.fissionmc.craft.compat.LecternInventory) inventory).getLectern();
        }
    }

    @Override
    public Lectern getHolder() {
        return (Lectern) this.inventory.getOwner();
    }
}
