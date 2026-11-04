package net.fissionmc.backend.mixin.world.item.crafting;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/item/crafting/RecipeHolder.java.patch
 * TODO(behaviour): Paper adds a record component runtimeAddedRecipe (+ asRuntimeAddedRecipe) used to prioritise
 * runtime-added recipes; Mixin cannot add record components.
 */
@Mixin(RecipeHolder.class)
public abstract class RecipeHolderMixin {
    @Shadow @Final private ResourceKey<Recipe<?>> id;
    @Shadow @Final private Recipe<?> value;

    // CraftBukkit start
    public org.bukkit.inventory.Recipe toBukkitRecipe() {
        return this.value.toBukkitRecipe(org.bukkit.craftbukkit.util.CraftNamespacedKey.fromMinecraft(this.id.identifier()));
    }
    // CraftBukkit end
}
