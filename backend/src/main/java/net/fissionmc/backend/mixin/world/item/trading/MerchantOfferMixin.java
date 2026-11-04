package net.fissionmc.backend.mixin.world.item.trading;

import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/item/trading/MerchantOffer.java.patch
 * Mixin cannot add Paper's constructor, so {@link #create} stands in for it.
 * TODO(behaviour): "Paper.IgnoreDiscounts" in CODEC, copy constructor/STREAM_CODEC carrying ignoreDiscounts,
 * preventNegativeVillagerDemand in updateDemand, empty-cost guard in getCostA.
 */
@Mixin(MerchantOffer.class)
public abstract class MerchantOfferMixin {
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private boolean ignoreDiscounts; // Paper - Add ignore discounts API

    // CraftBukkit start
    private org.bukkit.craftbukkit.inventory.@org.jspecify.annotations.Nullable CraftMerchantRecipe bukkitHandle;

    public org.bukkit.craftbukkit.inventory.CraftMerchantRecipe asBukkit() {
        return (this.bukkitHandle == null) ? this.bukkitHandle = new org.bukkit.craftbukkit.inventory.CraftMerchantRecipe((MerchantOffer) (Object) this) : this.bukkitHandle;
    }

    // Surface: public static. Replaces Paper's MerchantOffer(ItemCost, Optional, ItemStack, int, int, int, float, int, boolean, CraftMerchantRecipe).
    private static MerchantOffer create(ItemCost baseCostA, Optional<ItemCost> costB, ItemStack result, int uses, int maxUses, int experience, float priceMultiplier, int demand, final boolean ignoreDiscounts, org.bukkit.craftbukkit.inventory.CraftMerchantRecipe bukkit) { // Paper
        final MerchantOffer offer = new MerchantOffer(baseCostA, costB, result, uses, maxUses, experience, priceMultiplier, demand);
        ((MerchantOfferMixin) (Object) offer).ignoreDiscounts = ignoreDiscounts; // Paper
        ((MerchantOfferMixin) (Object) offer).bukkitHandle = bukkit;
        return offer;
    }
    // CraftBukkit end
}
