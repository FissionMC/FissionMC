package net.fissionmc.backend.mixin.world.item.crafting;

import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/item/crafting/Recipe.java.patch */
@Mixin(Recipe.class)
public interface RecipeMixin {
    // CraftBukkit - Paper declares this abstract; every vanilla recipe class overrides it in its own mixin.
    default org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        throw new UnsupportedOperationException("FissionMC: toBukkitRecipe not ported for " + this.getClass().getName());
    }
}
