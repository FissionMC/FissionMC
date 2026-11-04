package net.fissionmc.backend.mixin.world.item.crafting;

import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.ImbueRecipe;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/item/crafting/ImbueRecipe.java.patch */
@Mixin(ImbueRecipe.class)
public abstract class ImbueRecipeMixin {
    @Shadow @Final private ItemStackTemplate result;

    // CraftBukkit start
    public org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        final ImbueRecipe self = (ImbueRecipe) (Object) this;
        org.bukkit.inventory.ItemStack result = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(this.result);
        org.bukkit.craftbukkit.inventory.CraftComplexRecipe recipe = new org.bukkit.craftbukkit.inventory.CraftComplexRecipe(id, result, self);
        recipe.setGroup(self.group());
        recipe.setCategory(org.bukkit.craftbukkit.inventory.CraftRecipe.getCategory(self.category()));
        return recipe;
    }
    // CraftBukkit end
}
