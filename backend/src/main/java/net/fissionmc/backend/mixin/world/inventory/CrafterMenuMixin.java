package net.fissionmc.backend.mixin.world.inventory;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CrafterMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/inventory/CrafterMenu.java.patch */
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
@Mixin(CrafterMenu.class)
public abstract class CrafterMenuMixin extends AbstractContainerMenu {
    @Shadow @Final private net.minecraft.world.inventory.CraftingContainer container;
    @Shadow @Final private net.minecraft.world.inventory.ResultContainer resultContainer;
    @Shadow @Final private net.minecraft.world.entity.player.Player player;
    // CraftBukkit start
    private org.bukkit.craftbukkit.inventory.view.CraftCrafterView view = null;
    private Inventory fission$inventory;

    private CrafterMenuMixin() {
        super(null, 0);
    }

    @Override
    public org.bukkit.craftbukkit.inventory.view.CraftCrafterView getBukkitView() {
        if (this.view != null) {
            return this.view;
        }

        org.bukkit.craftbukkit.inventory.CraftInventoryCrafter inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryCrafter(this.container, this.resultContainer);
        this.view = new org.bukkit.craftbukkit.inventory.view.CraftCrafterView(this.player.getBukkitEntity(), inventory, (net.minecraft.world.inventory.CrafterMenu) (Object) this);
        return this.view;
    }
    // CraftBukkit end

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void fission$checkReachable(final Player player, final CallbackInfoReturnable<Boolean> cir) {
        if (!this.checkReachable) cir.setReturnValue(true); // CraftBukkit
    }
}
