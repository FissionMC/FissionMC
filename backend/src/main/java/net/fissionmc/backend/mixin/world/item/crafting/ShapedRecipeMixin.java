package net.fissionmc.backend.mixin.world.item.crafting;

import java.util.Optional;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/item/crafting/ShapedRecipe.java.patch
 * Paper returns the covariant org.bukkit.inventory.ShapedRecipe; kept as Recipe here so the method overrides
 * Recipe#toBukkitRecipe without a bridge. */
@Mixin(ShapedRecipe.class)
public abstract class ShapedRecipeMixin {
    @Shadow @Final private ShapedRecipePattern pattern;
    @Shadow @Final private ItemStackTemplate result;

    // CraftBukkit start
    public org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        final ShapedRecipe self = (ShapedRecipe) (Object) this;
        org.bukkit.inventory.ItemStack result = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(this.result);
        org.bukkit.craftbukkit.inventory.CraftShapedRecipe recipe = new org.bukkit.craftbukkit.inventory.CraftShapedRecipe(id, result, self);
        recipe.setGroup(self.group());
        recipe.setCategory(org.bukkit.craftbukkit.inventory.CraftRecipe.getCategory(self.category()));

        switch (this.pattern.height()) {
            case 1:
                switch (this.pattern.width()) {
                    case 1:
                        recipe.shape("a");
                        break;
                    case 2:
                        recipe.shape("ab");
                        break;
                    case 3:
                        recipe.shape("abc");
                        break;
                }
                break;
            case 2:
                switch (this.pattern.width()) {
                    case 1:
                        recipe.shape("a", "b");
                        break;
                    case 2:
                        recipe.shape("ab", "cd");
                        break;
                    case 3:
                        recipe.shape("abc", "def");
                        break;
                }
                break;
            case 3:
                switch (this.pattern.width()) {
                    case 1:
                        recipe.shape("a", "b", "c");
                        break;
                    case 2:
                        recipe.shape("ab", "cd", "ef");
                        break;
                    case 3:
                        recipe.shape("abc", "def", "ghi");
                        break;
                }
                break;
        }
        char c = 'a';
        for (Optional<Ingredient> ingredient : this.pattern.ingredients()) {
            org.bukkit.inventory.RecipeChoice choice = org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(ingredient);
            if (choice != org.bukkit.inventory.RecipeChoice.empty()) { // Paper
                recipe.setIngredient(c, choice);
            }

            c++;
        }
        return recipe;
    }
    // CraftBukkit end
}
