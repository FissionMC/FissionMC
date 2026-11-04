package net.fissionmc.backend.mixin.world.item.crafting;

import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/item/crafting/SmeltingRecipe.java.patch */
@Mixin(SmeltingRecipe.class)
public abstract class SmeltingRecipeMixin extends AbstractCookingRecipe {
    private SmeltingRecipeMixin() {
        super(null, null, null, null, 0.0F, 0);
    }

    // CraftBukkit start
    public org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        org.bukkit.inventory.ItemStack result = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(this.result());

        org.bukkit.craftbukkit.inventory.CraftFurnaceRecipe recipe = new org.bukkit.craftbukkit.inventory.CraftFurnaceRecipe(id, result, org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.input()), this.experience(), this.cookingTime());
        recipe.setGroup(this.group());
        recipe.setCategory(org.bukkit.craftbukkit.inventory.CraftRecipe.getCategory(this.category()));

        return recipe;
    }
    // CraftBukkit end
}
