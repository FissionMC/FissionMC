package net.fissionmc.backend.mixin.world.item.crafting;

import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.equipment.trim.TrimPattern;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/item/crafting/SmithingTrimRecipe.java.patch
 * Mixin cannot add Paper's constructor with copyDataComponents, so {@link #create} stands in for it.
 * TODO(behaviour): assemble() passes copyDataComponents to applyTrim; applyTrim(4 args) should hold the vanilla body
 * with the copyDataComponents switch (currently it only honours copyDataComponents == true).
 */
@Mixin(SmithingTrimRecipe.class)
public abstract class SmithingTrimRecipeMixin {
    @Shadow @Final private Ingredient template;
    @Shadow @Final private Ingredient base;
    @Shadow @Final private Ingredient addition;
    @Shadow @Final private Holder<TrimPattern> pattern;

    private boolean copyDataComponents = true; // Paper - Option to prevent data components copy

    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static SmithingTrimRecipe create(
        final Recipe.CommonInfo commonInfo, final Ingredient template, final Ingredient base, final Ingredient addition, final Holder<TrimPattern> pattern, final boolean copyDataComponents
    ) {
        final SmithingTrimRecipe recipe = new SmithingTrimRecipe(commonInfo, template, base, addition, pattern);
        ((SmithingTrimRecipeMixin) (Object) recipe).copyDataComponents = copyDataComponents; // Paper
        return recipe;
    }

    // Paper start - Option to prevent data components copy
    private static ItemStack applyTrim(final ItemStack baseItem, final ItemStack materialItem, final Holder<TrimPattern> pattern, final boolean copyDataComponents) {
        // TODO(behaviour): Paper uses new ItemStack(baseItem.getItem(), 1) instead of baseItem.copyWithCount(1) when !copyDataComponents
        return SmithingTrimRecipe.applyTrim(baseItem, materialItem, pattern);
    }
    // Paper end - Option to prevent data components copy

    // CraftBukkit start
    public org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        return new org.bukkit.craftbukkit.inventory.CraftSmithingTrimRecipe(id, org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.template), org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.base), org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.addition), org.bukkit.craftbukkit.inventory.trim.CraftTrimPattern.minecraftHolderToBukkit(this.pattern), this.copyDataComponents); // Paper - Option to prevent data components copy
    }
    // CraftBukkit end
}
