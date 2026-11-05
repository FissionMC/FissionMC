package net.fissionmc.backend.mixin.world.inventory;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/inventory/ChestMenu.java.patch */
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
@Mixin(ChestMenu.class)
public abstract class ChestMenuMixin extends AbstractContainerMenu {
    @Shadow @Final private Container container;
    // CraftBukkit start
    private org.bukkit.craftbukkit.inventory.CraftInventoryView view = null;
    private Inventory fission$inventory;

    private ChestMenuMixin() {
        super(null, 0);
    }

    // CraftBukkit start - Save player
    @Inject(method = "<init>(Lnet/minecraft/world/inventory/MenuType;ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/Container;I)V", at = @At("RETURN"))
    private void fission$savePlayer(final net.minecraft.world.inventory.MenuType<?> menuType, final int containerId, final Inventory inventory, final Container container, final int rows, final CallbackInfo ci) {
        this.fission$inventory = (Inventory) inventory;
    }
    // CraftBukkit end

    @Override
    public org.bukkit.craftbukkit.inventory.CraftInventoryView getBukkitView() {
        if (this.view != null) {
            return this.view;
        }

        org.bukkit.craftbukkit.inventory.CraftInventory inventory;
        if (this.container instanceof Inventory) {
            inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryPlayer((Inventory) this.container);
        } else if (this.container instanceof net.minecraft.world.CompoundContainer) {
            inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryDoubleChest((net.minecraft.world.CompoundContainer) this.container);
        } else {
            inventory = new org.bukkit.craftbukkit.inventory.CraftInventory(this.container);
        }

        this.view = new org.bukkit.craftbukkit.inventory.CraftInventoryView(this.fission$inventory.player.getBukkitEntity(), inventory, this);
        return this.view;
    }
    // CraftBukkit end

    // Paper - don't startOpen until menu actually opens.
    // FissionMC: vanilla still calls container.startOpen in the constructor until ServerPlayer#openMenu (player area)
    // calls AbstractContainerMenu#startOpen; startOpen() is therefore idempotent so the explicit call does not open twice.
    private boolean fission$startOpenCalled;

    @WrapOperation(method = "<init>(Lnet/minecraft/world/inventory/MenuType;ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/Container;I)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/Container;startOpen(Lnet/minecraft/world/entity/ContainerUser;)V"))
    private void fission$constructorStartOpen(final Container container, final net.minecraft.world.entity.ContainerUser user, final Operation<Void> original) {
        this.fission$startOpenCalled = true;
        original.call(container, user);
    }

    @Override
    public void startOpen() {
        if (this.fission$startOpenCalled) return;
        this.fission$startOpenCalled = true;
        this.container.startOpen(this.fission$inventory.player);
    }

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void fission$checkReachable(final Player player, final CallbackInfoReturnable<Boolean> cir) {
        if (!this.checkReachable) cir.setReturnValue(true); // CraftBukkit
    }
}
