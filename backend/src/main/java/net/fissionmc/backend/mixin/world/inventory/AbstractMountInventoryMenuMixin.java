package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractMountInventoryMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/inventory/AbstractMountInventoryMenu.java.patch
 * TODO(behaviour): SLOT_* made static (not possible with Mixin; ported code mirrors the constants), checkReachable in stillValid.
 */
@Mixin(AbstractMountInventoryMenu.class)
public abstract class AbstractMountInventoryMenuMixin extends AbstractContainerMenu {
    @Shadow @Final protected Container mountContainer;

    // CraftBukkit start
    private org.bukkit.craftbukkit.inventory.@org.jspecify.annotations.Nullable CraftInventoryView view;
    private Inventory inventory;

    private AbstractMountInventoryMenuMixin() {
        super(null, 0);
    }

    @Override
    public org.bukkit.inventory.InventoryView getBukkitView() {
        if (this.view != null) {
            return this.view;
        }

        return this.view = new org.bukkit.craftbukkit.inventory.CraftInventoryView(this.inventory.player.getBukkitEntity(), this.mountContainer.getOwner().getInventory(), this);
    }
    // CraftBukkit end

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fission$saveInventory(final int containerId, final Inventory playerInventory, final Container mountInventory, final LivingEntity mount, final CallbackInfo ci) {
        this.inventory = playerInventory; // CraftBukkit
    }
}
