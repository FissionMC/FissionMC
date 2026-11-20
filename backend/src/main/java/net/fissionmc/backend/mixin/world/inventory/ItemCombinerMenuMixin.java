package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.SmithingMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/inventory/ItemCombinerMenu.java.patch */
@Mixin(ItemCombinerMenu.class)
public abstract class ItemCombinerMenuMixin extends AbstractContainerMenu {
    private ItemCombinerMenuMixin() {
        super(null, 0);
    }

    @Inject(method = "slotsChanged", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/world/inventory/ItemCombinerMenu;createResult()V"))
    private void fission$prepareResult(final net.minecraft.world.Container container, final CallbackInfo ci) {
        org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareResultEvent(this, (Object) this instanceof SmithingMenu ? 3 : 2); // Paper - Add PrepareResultEvent
    }

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void fission$checkReachable(final Player player, final CallbackInfoReturnable<Boolean> cir) {
        if (!this.checkReachable) cir.setReturnValue(true); // CraftBukkit
    }

    @Shadow @Final protected net.minecraft.world.Container inputSlots;
    @Shadow @Final protected net.minecraft.world.inventory.ResultContainer resultSlots;

    // Paper - Add missing InventoryHolders (stands in for createContainer(size) / new ResultContainer(createBlockHolder(access)))
    @Inject(method = "<init>(Lnet/minecraft/world/inventory/MenuType;ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;Lnet/minecraft/world/inventory/ItemCombinerMenuSlotDefinition;)V", at = @At("RETURN"))
    private void fission$blockHolders(final net.minecraft.world.inventory.MenuType<?> menuType, final int containerId, final net.minecraft.world.entity.player.Inventory inventory,
                                      final net.minecraft.world.inventory.ContainerLevelAccess access, final net.minecraft.world.inventory.ItemCombinerMenuSlotDefinition slots, final CallbackInfo ci) {
        net.fissionmc.backend.blocks.PaperInventoryHolders.apply(this, access, this.inputSlots, this.resultSlots);
    }
}
