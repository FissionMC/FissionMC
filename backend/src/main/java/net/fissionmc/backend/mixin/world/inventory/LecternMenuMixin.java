package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.LecternMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/inventory/LecternMenu.java.patch
 * Paper replaces the constructors with ones taking the player inventory; Mixin cannot add constructors, so
 * {@link #create} stands in for {@code new LecternMenu(int, Container, ContainerData, Inventory)}.
 * TODO(behaviour): LecternMenu(int, Inventory) constructor, LecternBlockEntity#createMenu passing the inventory,
 * PlayerLecternPageChangeEvent / PlayerTakeLecternBookEvent in clickMenuButton, checkReachable in stillValid.
 */
@Mixin(LecternMenu.class)
public abstract class LecternMenuMixin extends AbstractContainerMenu {
    @Shadow @Final private Container lectern;

    // CraftBukkit start
    private org.bukkit.craftbukkit.inventory.view.@org.jspecify.annotations.Nullable CraftLecternView view = null;
    private org.bukkit.entity.Player player;

    private LecternMenuMixin() {
        super(null, 0);
    }

    @Override
    public org.bukkit.craftbukkit.inventory.view.CraftLecternView getBukkitView() {
        if (this.view != null) {
            return this.view;
        }

        org.bukkit.craftbukkit.inventory.CraftInventoryLectern inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryLectern(this.lectern);
        this.view = new org.bukkit.craftbukkit.inventory.view.CraftLecternView(this.player, inventory, (LecternMenu) (Object) this);
        return this.view;
    }
    // CraftBukkit end

    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static LecternMenu create(final int containerId, final Container lectern, final ContainerData lecternData, net.minecraft.world.entity.player.Inventory inventory) {
        final LecternMenu menu = new LecternMenu(containerId, lectern, lecternData);
        ((LecternMenuMixin) (Object) menu).player = (org.bukkit.entity.Player) inventory.player.getBukkitEntity(); // CraftBukkit
        return menu;
    }
}
