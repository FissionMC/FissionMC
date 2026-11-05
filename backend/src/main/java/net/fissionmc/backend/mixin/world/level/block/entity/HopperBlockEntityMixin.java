package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/block/entity/HopperBlockEntity.java.patch */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @Shadow private net.minecraft.core.NonNullList<ItemStack> items;

    // CraftBukkit start - add fields and methods
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = net.minecraft.world.item.Item.ABSOLUTE_MAX_STACK_SIZE;

    public java.util.List<ItemStack> getContents() {
        return this.items;
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

    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }
    // CraftBukkit end

    @Shadow private static Container getAttachedContainer(Level level, BlockPos pos, HopperBlockEntity self) { throw new AssertionError(); }
    @Shadow private static boolean isFullContainer(Container container, Direction direction) { throw new AssertionError(); }
    @Shadow private static boolean canTakeItemFromContainer(Container into, Container from, ItemStack stack, int slot, Direction direction) { throw new AssertionError(); }

    private static org.bukkit.inventory.Inventory fission$getInventory(final Container container) {
        final org.bukkit.inventory.Inventory sourceInventory;
        if (container instanceof net.minecraft.world.CompoundContainer compoundContainer) {
            sourceInventory = new org.bukkit.craftbukkit.inventory.CraftInventoryDoubleChest(compoundContainer);
        } else if (container.getOwner() != null) {
            sourceInventory = container.getOwner().getInventory();
        } else {
            sourceInventory = new org.bukkit.craftbukkit.inventory.CraftInventory(container);
        }
        return sourceInventory;
    }

    // Paper - Add PrepareHopperTransferEvent
    private static long fission$callPrepareTransferEvent(final Level level, final Container source, final Container destination, final boolean didSourceInitiate) {
        if (io.papermc.paper.event.inventory.PrepareHopperTransferEvent.getHandlerList().getRegisteredListeners().length == 0 || level.paperConfig().hopper.disableMoveEvent || source.isEmpty()) {
            return 0L;
        }
        final io.papermc.paper.event.inventory.PaperPrepareHopperTransferEvent event = new io.papermc.paper.event.inventory.PaperPrepareHopperTransferEvent(fission$getInventory(source), fission$getInventory(destination), didSourceInitiate);
        event.callEvent();
        return event.getDisallowedSlots();
    }

    private static boolean fission$isSlotDisallowed(final long disallowedSlots, final int slot) {
        return (disallowedSlots & (1L << slot)) != 0;
    }

    private static Level fission$levelOf(final Hopper hopper) {
        if (hopper instanceof net.minecraft.world.entity.Entity entity) return entity.level();
        return ((net.minecraft.world.level.block.entity.BlockEntity) hopper).getLevel();
    }

    // replaced body: ejectItems with InventoryMoveItemEvent (hopper -> container)
    @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true)
    private static void fission$ejectItems(final Level level, final BlockPos blockPos, final HopperBlockEntity self, final CallbackInfoReturnable<Boolean> cir) {
        Container container = getAttachedContainer(level, blockPos, self);
        if (container == null) {
            cir.setReturnValue(false);
            return;
        }
        Direction direction = self.getBlockState().getValue(HopperBlock.FACING).getOpposite();
        if (isFullContainer(container, direction)) {
            cir.setReturnValue(false);
            return;
        }

        final long disallowedSlots = fission$callPrepareTransferEvent(level, self, container, true); // Paper - Add PrepareHopperTransferEvent
        for (int slot = 0; slot < self.getContainerSize(); slot++) {
            if (fission$isSlotDisallowed(disallowedSlots, slot)) continue; // Paper - Add PrepareHopperTransferEvent
            ItemStack itemStack = self.getItem(slot);
            if (!itemStack.isEmpty()) {
                int originalCount = itemStack.getCount();
                ItemStack original = itemStack.copy();
                org.bukkit.inventory.ItemStack oitemstack = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(
                    self.removeItem(slot, level.spigotConfig.hopperAmount) // Spigot
                );

                final org.bukkit.inventory.Inventory destinationInventory = fission$getInventory(container); // Paper - Add PrepareHopperTransferEvent

                org.bukkit.event.inventory.InventoryMoveItemEvent event = new org.bukkit.event.inventory.InventoryMoveItemEvent(
                    self.getOwner().getInventory(),
                    oitemstack,
                    destinationInventory,
                    true
                );
                if (!event.callEvent()) {
                    self.setItem(slot, original);
                    self.setCooldown(level.spigotConfig.hopperTransfer); // Delay hopper checks // Spigot
                    cir.setReturnValue(false);
                    return;
                }
                int origCount = event.getItem().getAmount(); // Spigot
                ItemStack result = HopperBlockEntity.addItem(self, container, org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem()), direction);
                if (result.isEmpty()) {
                    container.setChanged();
                    cir.setReturnValue(true);
                    return;
                }

                itemStack.setCount(originalCount);
                itemStack.shrink(origCount - result.getCount());
                if (originalCount <= level.spigotConfig.hopperAmount) {
                    self.setItem(slot, itemStack);
                }
            }
        }

        cir.setReturnValue(false);
    }

    // PrepareHopperTransferEvent for suckInItems
    // replaced body: tryTakeInItemFromSlot with InventoryMoveItemEvent (container -> hopper)
    @Inject(method = "tryTakeInItemFromSlot", at = @At("HEAD"), cancellable = true)
    private static void fission$tryTakeInItemFromSlot(final Hopper hopper, final Container container, final int slot, final Direction direction, final CallbackInfoReturnable<Boolean> cir) {
        final Level level = fission$levelOf(hopper); // Spigot
        ItemStack itemStack = container.getItem(slot);
        if (!itemStack.isEmpty() && canTakeItemFromContainer(hopper, container, itemStack, slot, direction)) {
            int originalCount = itemStack.getCount();
            ItemStack original = itemStack.copy();
            org.bukkit.inventory.ItemStack oitemstack = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(
                container.removeItem(slot, level.spigotConfig.hopperAmount) // Spigot
            );

            final org.bukkit.inventory.Inventory sourceInventory = fission$getInventory(container); // Paper - Add PrepareHopperTransferEvent

            org.bukkit.event.inventory.InventoryMoveItemEvent event = new org.bukkit.event.inventory.InventoryMoveItemEvent(
                sourceInventory,
                oitemstack,
                hopper.getOwner().getInventory(),
                false
            );

            if (!event.callEvent()) {
                container.setItem(slot, original);

                if (hopper instanceof final HopperBlockEntity hopperBlockEntity) {
                    hopperBlockEntity.setCooldown(level.spigotConfig.hopperTransfer); // Spigot
                }

                cir.setReturnValue(false);
                return;
            }
            int origCount = event.getItem().getAmount(); // Spigot
            ItemStack result = HopperBlockEntity.addItem(container, hopper, org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem()), null);
            if (result.isEmpty()) {
                container.setChanged();
                cir.setReturnValue(true);
                return;
            }

            itemStack.setCount(originalCount);
            itemStack.shrink(origCount - result.getCount());
            if (originalCount <= level.spigotConfig.hopperAmount) {
                container.setItem(slot, itemStack);
            }
        }

        cir.setReturnValue(false);
    }

    // InventoryPickupItemEvent
    @Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z", at = @At("HEAD"), cancellable = true)
    private static void fission$pickupEvent(final Container container, final ItemEntity entity, final CallbackInfoReturnable<Boolean> cir) {
        org.bukkit.event.inventory.InventoryPickupItemEvent event = new org.bukkit.event.inventory.InventoryPickupItemEvent(
            container.getOwner().getInventory(), (org.bukkit.entity.Item) entity.getBukkitEntity()
        );
        if (!event.callEvent()) {
            cir.setReturnValue(false);
        }
    }

    // entity.discard() -> entity.discard(EntityRemoveEvent.Cause.PICKUP)
    @WrapOperation(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V"))
    private static void fission$discardCause(final ItemEntity entity, final Operation<Void> original) {
        entity.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.PICKUP); // CraftBukkit - add Bukkit remove cause
    }

    // TODO(behaviour): HopperInventorySearchEvent (getAttachedContainer/getSourceContainer), PrepareHopperTransferEvent in suckInItems,
    // max-stack-size respect in addItem/tryMoveInItem (Paper), pushItemsTick hopperCheck cooldown, spigot hopperTransfer cooldown in tryMoveItems/tryMoveInItem, hopperCanLoadChunks.
}
