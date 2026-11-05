package net.fissionmc.backend.mixin.world.inventory;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/inventory/AbstractFurnaceMenu.java.patch */
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
@Mixin(AbstractFurnaceMenu.class)
public abstract class AbstractFurnaceMenuMixin extends net.minecraft.world.inventory.RecipeBookMenu {
    @Shadow @Final private Container container;
    // CraftBukkit start
    private org.bukkit.craftbukkit.inventory.view.CraftFurnaceView view = null;
    private Inventory fission$inventory;

    private AbstractFurnaceMenuMixin() {
        super(null, 0);
    }

    // CraftBukkit start - Save player
    @Inject(method = "<init>(Lnet/minecraft/world/inventory/MenuType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/inventory/RecipeBookType;ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/Container;Lnet/minecraft/world/inventory/ContainerData;)V", at = @At("RETURN"))
    private void fission$savePlayer(final net.minecraft.world.inventory.MenuType<?> menuType, final net.minecraft.resources.ResourceKey<?> key, final net.minecraft.world.inventory.RecipeBookType type, final int containerId, final Inventory inventory, final Container container, final net.minecraft.world.inventory.ContainerData data, final CallbackInfo ci) {
        this.fission$inventory = (Inventory) inventory;
    }
    // CraftBukkit end

    @Override
    public org.bukkit.craftbukkit.inventory.view.CraftFurnaceView getBukkitView() {
        if (this.view != null) {
            return this.view;
        }

        org.bukkit.craftbukkit.inventory.CraftInventoryFurnace inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryFurnace((net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) this.container);
        this.view = new org.bukkit.craftbukkit.inventory.view.CraftFurnaceView(this.fission$inventory.player.getBukkitEntity(), inventory, (net.minecraft.world.inventory.AbstractFurnaceMenu) (Object) this);
        return this.view;
    }
    // CraftBukkit end

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void fission$checkReachable(final Player player, final CallbackInfoReturnable<Boolean> cir) {
        if (!this.checkReachable) cir.setReturnValue(true); // CraftBukkit
    }
}
