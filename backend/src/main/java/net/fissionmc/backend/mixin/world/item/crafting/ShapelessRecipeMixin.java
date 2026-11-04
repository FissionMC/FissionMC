package net.fissionmc.backend.mixin.world.item.crafting;

import java.util.List;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/item/crafting/ShapelessRecipe.java.patch
 * Paper returns the covariant org.bukkit.inventory.ShapelessRecipe; kept as Recipe here so the method overrides
 * Recipe#toBukkitRecipe without a bridge. */
@Mixin(ShapelessRecipe.class)
public abstract class ShapelessRecipeMixin {
    @Shadow @Final private ItemStackTemplate result;
    @Shadow @Final private List<Ingredient> ingredients;

    // CraftBukkit start
    public org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        final ShapelessRecipe self = (ShapelessRecipe) (Object) this;
        org.bukkit.inventory.ItemStack result = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(this.result);
        org.bukkit.craftbukkit.inventory.CraftShapelessRecipe recipe = new org.bukkit.craftbukkit.inventory.CraftShapelessRecipe(id, result, self);
        recipe.setGroup(self.group());
        recipe.setCategory(org.bukkit.craftbukkit.inventory.CraftRecipe.getCategory(self.category()));

        for (Ingredient ingredient : this.ingredients) {
            recipe.addIngredient(org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(ingredient));
        }
        return recipe;
    }
    // CraftBukkit end
}
