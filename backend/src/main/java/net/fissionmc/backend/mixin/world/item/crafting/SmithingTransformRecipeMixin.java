package net.fissionmc.backend.mixin.world.item.crafting;

import java.util.Optional;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/item/crafting/SmithingTransformRecipe.java.patch
 * Mixin cannot add Paper's constructor with copyDataComponents, so {@link #create} stands in for it.
 * TODO(behaviour): assemble() returns this.result.create() when !copyDataComponents.
 */
@Mixin(SmithingTransformRecipe.class)
public abstract class SmithingTransformRecipeMixin {
    @Shadow @Final private Optional<Ingredient> template;
    @Shadow @Final private Ingredient base;
    @Shadow @Final private Optional<Ingredient> addition;
    @Shadow @Final private ItemStackTemplate result;

    private boolean copyDataComponents = true; // Paper - Option to prevent data components copy

    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static SmithingTransformRecipe create(
        final Recipe.CommonInfo commonInfo,
        final Optional<Ingredient> template,
        final Ingredient base,
        final Optional<Ingredient> addition,
        final ItemStackTemplate result,
        final boolean copyDataComponents
    ) {
        final SmithingTransformRecipe recipe = new SmithingTransformRecipe(commonInfo, template, base, addition, result);
        ((SmithingTransformRecipeMixin) (Object) recipe).copyDataComponents = copyDataComponents;
        return recipe;
    }

    // CraftBukkit start
    public org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        org.bukkit.inventory.ItemStack result = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(this.result);

        return new org.bukkit.craftbukkit.inventory.CraftSmithingTransformRecipe(id, result, org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.template), org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.base), org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.addition), this.copyDataComponents);
    }
    // CraftBukkit end
}
