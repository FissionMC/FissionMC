package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/world/inventory/CraftingMenu.java.patch
 * PrepareItemCraftEvent + checkReachable ported; left (perf): current-recipe caching.
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin extends AbstractCraftingMenu {
    @Shadow @Final private Player player;

    private org.bukkit.craftbukkit.inventory.@Nullable CraftInventoryView view = null; // CraftBukkit

    private CraftingMenuMixin() {
        super(null, 0, 0, 0);
    }

    // CraftBukkit start
    @Override
    public org.bukkit.craftbukkit.inventory.CraftInventoryView getBukkitView() {
        if (this.view != null) {
            return this.view;
        }

        org.bukkit.craftbukkit.inventory.CraftInventoryCrafting inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryCrafting(this.craftSlots, this.resultSlots);
        this.view = new org.bukkit.craftbukkit.inventory.CraftInventoryView(this.player.getBukkitEntity(), inventory, this);
        return this.view;
    }
    // CraftBukkit end

    @Inject(method = "slotChangedCraftingGrid", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/ResultContainer;setItem(ILnet/minecraft/world/item/ItemStack;)V"))
    private static void fission$prepareItemCraft(final net.minecraft.world.inventory.AbstractContainerMenu menu, final ServerLevel level, final Player player, final CraftingContainer container, final ResultContainer resultSlots, final @Nullable RecipeHolder<CraftingRecipe> recipeHint, final CallbackInfo ci,
                                                 @Local(ordinal = 0) LocalRef<ItemStack> result, @Local java.util.Optional<RecipeHolder<CraftingRecipe>> maybeRecipe) {
        result.set(org.bukkit.craftbukkit.event.CraftEventFactory.callPreCraftEvent(container, resultSlots, result.get(), menu.getBukkitView(), maybeRecipe.map(RecipeHolder::value).orElse(null) instanceof net.minecraft.world.item.crafting.RepairItemRecipe)); // CraftBukkit
    }

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void fission$checkReachable(final Player player, final CallbackInfoReturnable<Boolean> cir) {
        if (!this.checkReachable) cir.setReturnValue(true); // CraftBukkit
    }
}
