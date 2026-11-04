package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.world.inventory.CraftingContainer;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/inventory/CraftingContainer.java.patch */
@Mixin(CraftingContainer.class)
public interface CraftingContainerMixin {
    // CraftBukkit start
    default net.minecraft.world.item.crafting.@org.jspecify.annotations.Nullable RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe> getCurrentRecipe() {
        return null;
    }

    default void setCurrentRecipe(net.minecraft.world.item.crafting.@org.jspecify.annotations.Nullable RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe> recipe) {
    }
    // CraftBukkit end
}
