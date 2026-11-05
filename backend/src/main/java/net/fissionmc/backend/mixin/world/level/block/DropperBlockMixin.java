package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/block/DropperBlock.java.patch (BlockFailedDispenseEvent, BlockPreDispenseEvent, InventoryMoveItemEvent) */
@Mixin(DropperBlock.class)
public abstract class DropperBlockMixin {
    @Inject(method = "dispenseFrom", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;levelEvent(ILnet/minecraft/core/BlockPos;I)V", ordinal = 0), cancellable = true)
    private void fission$failedDispense(final ServerLevel level, final BlockState state, final BlockPos pos, final CallbackInfo ci) {
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFailedDispenseEvent(level, pos)) ci.cancel(); // Paper - Add BlockFailedDispenseEvent
    }

    @Inject(method = "dispenseFrom", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/dispenser/DispenseItemBehavior;dispense(Lnet/minecraft/core/dispenser/BlockSource;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;", ordinal = 0), cancellable = true)
    private void fission$preDispense(final ServerLevel level, final BlockState state, final BlockPos pos, final CallbackInfo ci, @Local(name = "slot") int slot, @Local(name = "itemStack") ItemStack itemStack) {
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockPreDispenseEvent(level, pos, itemStack, slot)) ci.cancel(); // Paper - Add BlockPreDispenseEvent
    }

    // InventoryMoveItemEvent when dropping into a container. A cancelled event / changed item returns a non-empty stand-in so vanilla keeps the stack.
    @WrapOperation(method = "dispenseFrom", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack fission$moveEvent(final Container blockEntity, final Container into, final ItemStack single, final Direction direction, final Operation<ItemStack> original) {
        org.bukkit.inventory.ItemStack oitemstack = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(single);

        org.bukkit.inventory.Inventory destinationInventory;
        if (into instanceof net.minecraft.world.CompoundContainer compoundContainer) {
            destinationInventory = new org.bukkit.craftbukkit.inventory.CraftInventoryDoubleChest(compoundContainer);
        } else {
            destinationInventory = into.getOwner().getInventory();
        }

        org.bukkit.event.inventory.InventoryMoveItemEvent event = new org.bukkit.event.inventory.InventoryMoveItemEvent(blockEntity.getOwner().getInventory(), oitemstack, destinationInventory, true);
        if (!event.callEvent()) {
            return single.copy(); // non-empty: vanilla restores the original stack
        }
        ItemStack remaining = original.call(blockEntity, into, org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem()), direction);
        if (event.getItem().equals(oitemstack) && remaining.isEmpty()) {
            return remaining;
        }
        return single.copy();
    }
}
