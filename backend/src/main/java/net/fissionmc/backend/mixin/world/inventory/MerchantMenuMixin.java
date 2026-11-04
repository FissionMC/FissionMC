package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/inventory/MerchantMenu.java.patch
 * TODO(behaviour): checkReachable in stillValid, PlayerTradeEvent/PlayerPurchaseEvent in quickMoveStack, SPIGOT-5035 in playTradeSound.
 */
@Mixin(MerchantMenu.class)
public abstract class MerchantMenuMixin extends AbstractContainerMenu {
    @Shadow @Final private Merchant trader;
    @Shadow @Final private MerchantContainer tradeContainer;

    // CraftBukkit start
    private org.bukkit.craftbukkit.inventory.view.@org.jspecify.annotations.Nullable CraftMerchantView view = null;
    private Inventory inventory;

    private MerchantMenuMixin() {
        super(null, 0);
    }

    @Override
    public org.bukkit.craftbukkit.inventory.view.CraftMerchantView getBukkitView() {
        if (this.view == null) {
            this.view = new org.bukkit.craftbukkit.inventory.view.CraftMerchantView(this.inventory.player.getBukkitEntity(), new org.bukkit.craftbukkit.inventory.CraftInventoryMerchant(this.trader, this.tradeContainer), (MerchantMenu) (Object) this, this.trader);
        }
        return this.view;
    }
    // CraftBukkit end

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/item/trading/Merchant;)V", at = @At("TAIL"))
    private void fission$saveInventory(final int containerId, final Inventory inventory, final Merchant merchant, final CallbackInfo ci) {
        this.inventory = inventory; // CraftBukkit
    }
}
