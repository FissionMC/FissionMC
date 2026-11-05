package net.fissionmc.backend.mixin.world.inventory;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/inventory/StonecutterMenu.java.patch */
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
@Mixin(StonecutterMenu.class)
public abstract class StonecutterMenuMixin extends AbstractContainerMenu {
    @Shadow @Final public Container container;
    @Shadow @Final private net.minecraft.world.inventory.ResultContainer resultContainer;
    // CraftBukkit start
    private org.bukkit.craftbukkit.inventory.view.CraftStonecutterView view = null;
    private Inventory fission$inventory;

    private StonecutterMenuMixin() {
        super(null, 0);
    }

    // CraftBukkit start - Save player
    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V", at = @At("RETURN"))
    private void fission$savePlayer(final int containerId, final Inventory inventory, final net.minecraft.world.inventory.ContainerLevelAccess access, final CallbackInfo ci) {
        this.fission$inventory = (Inventory) inventory;
    }
    // CraftBukkit end

    @Override
    public org.bukkit.craftbukkit.inventory.view.CraftStonecutterView getBukkitView() {
        if (this.view != null) {
            return this.view;
        }

        org.bukkit.craftbukkit.inventory.CraftInventoryStonecutter inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryStonecutter(this.container, this.resultContainer);
        this.view = new org.bukkit.craftbukkit.inventory.view.CraftStonecutterView((org.bukkit.entity.Player) this.fission$inventory.player.getBukkitEntity(), inventory, (net.minecraft.world.inventory.StonecutterMenu) (Object) this);
        return this.view;
    }
    // CraftBukkit end

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void fission$checkReachable(final Player player, final CallbackInfoReturnable<Boolean> cir) {
        if (!this.checkReachable) cir.setReturnValue(true); // CraftBukkit
    }
}
