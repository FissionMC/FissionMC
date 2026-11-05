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
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    // CraftBukkit start - Store slots from drag in map (raw slot id -> new stack)
    private final it.unimi.dsi.fastutil.ints.Int2ObjectMap<ItemStack> fission$draggedSlots = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    @Shadow private int quickcraftType;
    @Shadow private ItemStack carried;
    @Shadow public abstract void setCarried(ItemStack stack);

    // Paper - Add slot sanity checks to container clicks
    @Inject(method = "doClick", at = @At("HEAD"), cancellable = true)
    private void fission$slotSanity(final int slotIndex, final int buttonNum, final ContainerInput containerInput, final Player player, final CallbackInfo ci) {
        if (slotIndex < 0 && slotIndex != -999) {
            if (containerInput == ContainerInput.SWAP
                || (containerInput == ContainerInput.QUICK_CRAFT && AbstractContainerMenu.getQuickcraftHeader(buttonNum) == 1)) {
                ci.cancel();
            }
        }
    }

    // slot.setByPlayer(source.copyWithCount(newCount)); -> draggedSlots.put(...)
    @WrapOperation(method = "doClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;setByPlayer(Lnet/minecraft/world/item/ItemStack;)V", ordinal = 0))
    private void fission$collectDrag(final Slot slot, final ItemStack stack, final Operation<Void> original) {
        this.fission$draggedSlots.put(slot.index, stack);
    }

    // this.setCarried(source) after the drag loop -> InventoryDragEvent
    @WrapOperation(method = "doClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;setCarried(Lnet/minecraft/world/item/ItemStack;)V", ordinal = 0))
    private void fission$dragEvent(final AbstractContainerMenu self, final ItemStack source, final Operation<Void> original) {
        final it.unimi.dsi.fastutil.ints.Int2ObjectMap<ItemStack> draggedSlots = this.fission$draggedSlots;
        final int remaining = source.getCount();
        // CraftBukkit start - InventoryDragEvent
        org.bukkit.inventory.InventoryView view = self.getBukkitView();
        org.bukkit.inventory.ItemStack newCarried = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(source);
        newCarried.setAmount(remaining);
        java.util.Map<Integer, org.bukkit.inventory.ItemStack> eventMap = new java.util.HashMap<>();
        for (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<ItemStack> entry : draggedSlots.int2ObjectEntrySet()) {
            eventMap.put(entry.getIntKey(), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(entry.getValue()));
        }

        // It's essential that we set the cursor to the new value here to prevent item duplication if a plugin closes the inventory.
        ItemStack oldCarried = self.getCarried();
        self.setCarried(org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(newCarried));

        org.bukkit.event.inventory.InventoryDragEvent event = new org.bukkit.event.inventory.InventoryDragEvent(
            view,
            newCarried,
            org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(oldCarried),
            this.quickcraftType == 1, // QUICKCRAFT_TYPE_GREEDY
            eventMap
        );
        event.callEvent();

        if (event.getResult() != org.bukkit.event.Event.Result.DENY) {
            for (final it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<ItemStack> entry : draggedSlots.int2ObjectEntrySet()) {
                view.setItem(entry.getIntKey(), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(entry.getValue()));
            }
            self.setCarried(org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getCursor()));
        } else {
            self.setCarried(oldCarried);
        }
        draggedSlots.clear();
        // CraftBukkit end
    }

    // Drop outside: setCarried(EMPTY) before drop
    @WrapOperation(method = "doClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;", ordinal = 0))
    private net.minecraft.world.entity.item.ItemEntity fission$dropCarried(final Player player, final ItemStack stack, final boolean thrown, final net.minecraft.util.Prediction prediction, final Operation<net.minecraft.world.entity.item.ItemEntity> original) {
        // CraftBukkit start
        this.setCarried(ItemStack.EMPTY);
        return original.call(player, stack, thrown, prediction);
        // CraftBukkit end
    }

    // SPIGOT-4556: setCarried(EMPTY) before dropOrPlaceInInventory
    @Inject(method = "removed", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;dropOrPlaceInInventory(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V"))
    private void fission$clearCarriedFirst(final Player player, final CallbackInfo ci) {
        this.setCarried(ItemStack.EMPTY); // CraftBukkit - SPIGOT-4556 - from below
    }

    // CraftBukkit - getCarried: normalise empty carried
    @Inject(method = "getCarried", at = @At("HEAD"))
    private void fission$normaliseCarried(final CallbackInfoReturnable<ItemStack> cir) {
        if (this.carried.isEmpty()) {
            this.setCarried(ItemStack.EMPTY);
        }
    }

    // Paper - Make sure player is dead (https://github.com/PaperMC/Paper/pull/13002)
    @WrapOperation(method = "dropOrPlaceInInventory", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isRemoved()Z"))
    private static boolean fission$notAlive(final net.minecraft.server.level.ServerPlayer player, final Operation<Boolean> original) {
        return !player.isAlive();
    }
}
