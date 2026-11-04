package net.fissionmc.backend.mixin.world.item.trading;

import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/item/trading/Merchant.java.patch */
@Mixin(Merchant.class)
public interface MerchantMixin {
    default void processTrade(MerchantOffer offer, io.papermc.paper.event.player.@Nullable PlayerPurchaseEvent event) { ((Merchant) this).notifyTrade(offer); } // Paper

    // CraftBukkit - Paper declares this abstract; implementors without a ported override end up here.
    // TODO(behaviour): AbstractVillager (entity area) and ClientSideMerchant need their getCraftMerchant() overrides.
    default org.bukkit.craftbukkit.inventory.CraftMerchant getCraftMerchant() {
        throw new UnsupportedOperationException("FissionMC: getCraftMerchant not ported for " + this.getClass().getName());
    }
}
