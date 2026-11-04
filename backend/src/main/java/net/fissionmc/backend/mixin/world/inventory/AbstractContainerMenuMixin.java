package net.fissionmc.backend.mixin.world.inventory;

import java.util.OptionalInt;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.RemoteSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/inventory/AbstractContainerMenu.java.patch */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Shadow @Final public NonNullList<Slot> slots;
    @Shadow @Final private NonNullList<RemoteSlot> remoteSlots;
    @Shadow private RemoteSlot remoteCarried;
    @Shadow private @Nullable ContainerSynchronizer synchronizer;

    @Shadow public abstract void sendAllDataToRemote();
    @Shadow public abstract OptionalInt findSlot(Container container, int slotIndex);
    @Shadow public abstract ItemStack getCarried();

    // CraftBukkit start
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private boolean checkReachable = true;

    // Paper declares this abstract and every menu overrides it; menus without a ported override end up here.
    // TODO(behaviour): port getBukkitView() of the remaining menus (they need state Paper captures in their constructors).
    public org.bukkit.inventory.InventoryView getBukkitView() {
        throw new UnsupportedOperationException("FissionMC: getBukkitView not ported for " + this.getClass().getName());
    }

    public void transferTo(AbstractContainerMenu other, org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        org.bukkit.inventory.InventoryView source = this.getBukkitView(), destination = other.getBukkitView();
        ((org.bukkit.craftbukkit.inventory.CraftInventory) source.getTopInventory()).getInventory().onClose(player);
        ((org.bukkit.craftbukkit.inventory.CraftInventory) source.getBottomInventory()).getInventory().onClose(player);
        ((org.bukkit.craftbukkit.inventory.CraftInventory) destination.getTopInventory()).getInventory().onOpen(player);
        ((org.bukkit.craftbukkit.inventory.CraftInventory) destination.getBottomInventory()).getInventory().onOpen(player);
    }

    private net.minecraft.network.chat.@Nullable Component title;

    public final Component getTitle() {
        // Paper start - return chat component with empty text instead of throwing error
        if (this.title == null) {
            return Component.empty();
        }
        // Paper end - return chat component with empty text instead of throwing error
        return this.title;
    }

    public final void setTitle(Component title) {
        com.google.common.base.Preconditions.checkState(this.title == null, "Title already set");
        this.title = title;
    }

    public void startOpen() {}
    // CraftBukkit end

    // Paper start
    public void forceHeldSlot(final net.minecraft.world.InteractionHand hand) {
        this.sendAllDataToRemote();
    }

    public void forceHeldSlotAndArmor(final net.minecraft.world.InteractionHand hand) {
        this.sendAllDataToRemote();
    }

    public void forceSlot(final Container container, final int slot) {
        final int slotsIndex = this.findSlot(container, slot).orElse(-1);
        if (slotsIndex == -1) {
            return;
        }

        final ItemStack item = this.slots.get(slotsIndex).getItem();
        this.remoteSlots.get(slotsIndex).force(item);

        if (this.synchronizer != null) {
            this.synchronizer.sendSlotChange((AbstractContainerMenu) (Object) this, slotsIndex, item.copy());
        }
    }
    // Paper end

    // CraftBukkit start - from synchronizeCarriedToRemote
    public void broadcastCarriedItem() {
        ItemStack carried = this.getCarried();
        this.remoteCarried.force(carried);
        if (this.synchronizer != null) {
            this.synchronizer.sendCarriedChange((AbstractContainerMenu) (Object) this, carried.copy());
        }
    }
    // CraftBukkit end
}
