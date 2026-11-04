package net.fissionmc.backend.mixin.world.item.crafting;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/item/crafting/Ingredient.java.patch
 * TODO(behaviour): test() honours stackPredicate and exact itemStacks; equals() compares itemStacks.
 */
@Mixin(Ingredient.class)
public abstract class IngredientMixin {
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private java.util.function.@Nullable Predicate<ItemStack> stackPredicate; // Paper - add PredicateChoice
    // CraftBukkit start
    private java.util.@Nullable List<ItemStack> itemStacks;

    public boolean isExact() {
        return this.itemStacks != null;
    }

    public java.util.@Nullable List<ItemStack> itemStacks() {
        return this.itemStacks;
    }

    // Surface: public static.
    private static Ingredient ofStacks(java.util.List<ItemStack> stacks) {
        Ingredient ingredient = Ingredient.of(stacks.stream().map(ItemStack::getItem));
        ((IngredientMixin) (Object) ingredient).itemStacks = stacks;
        return ingredient;
    }
    // CraftBukkit end
}
