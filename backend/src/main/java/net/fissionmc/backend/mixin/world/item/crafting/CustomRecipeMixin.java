package net.fissionmc.backend.mixin.world.item.crafting;

import net.minecraft.world.item.crafting.CustomRecipe;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/item/crafting/CustomRecipe.java.patch */
@Mixin(CustomRecipe.class)
public abstract class CustomRecipeMixin {
    // CraftBukkit start
    public org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        final CustomRecipe self = (CustomRecipe) (Object) this;
        org.bukkit.craftbukkit.inventory.CraftComplexRecipe recipe = new org.bukkit.craftbukkit.inventory.CraftComplexRecipe(id, org.bukkit.inventory.ItemStack.empty(), self);
        recipe.setGroup(self.group());
        recipe.setCategory(org.bukkit.craftbukkit.inventory.CraftRecipe.getCategory(self.category()));

        return recipe;
    }
    // CraftBukkit end
}
