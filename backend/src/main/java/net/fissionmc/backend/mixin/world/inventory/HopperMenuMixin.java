package net.fissionmc.backend.mixin.world.inventory;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HopperMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/inventory/HopperMenu.java.patch */
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
@Mixin(HopperMenu.class)
public abstract class HopperMenuMixin extends AbstractContainerMenu {
    @Shadow @Final private Container hopper;
    // CraftBukkit start
    private org.bukkit.craftbukkit.inventory.CraftInventoryView view = null;
    private Inventory fission$inventory;

    private HopperMenuMixin() {
        super(null, 0);
    }

    // CraftBukkit start - Save player
    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/Container;)V", at = @At("RETURN"))
    private void fission$savePlayer(final int containerId, final Inventory inventory, final Container hopper, final CallbackInfo ci) {
        this.fission$inventory = (Inventory) inventory;
    }
    // CraftBukkit end

    @Override
    public org.bukkit.craftbukkit.inventory.CraftInventoryView getBukkitView() {
        if (this.view != null) {
            return this.view;
        }

        org.bukkit.craftbukkit.inventory.CraftInventory inventory = new org.bukkit.craftbukkit.inventory.CraftInventory(this.hopper);
        this.view = new org.bukkit.craftbukkit.inventory.CraftInventoryView(this.fission$inventory.player.getBukkitEntity(), inventory, this);
        return this.view;
    }
    // CraftBukkit end

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void fission$checkReachable(final Player player, final CallbackInfoReturnable<Boolean> cir) {
        if (!this.checkReachable) cir.setReturnValue(true); // CraftBukkit
    }
}
