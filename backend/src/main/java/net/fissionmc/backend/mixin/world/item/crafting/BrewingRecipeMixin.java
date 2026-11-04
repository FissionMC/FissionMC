package net.fissionmc.backend.mixin.world.item.crafting;

import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.BrewingRecipe;
import net.minecraft.world.item.crafting.PotionIngredient;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/item/crafting/BrewingRecipe.java.patch */
@Mixin(BrewingRecipe.class)
public abstract class BrewingRecipeMixin {
    @Shadow @Final private PotionIngredient input;
    @Shadow @Final private PotionIngredient reagent;
    @Shadow @Final private ItemStackTemplate output;

    // Paper start - Recipe API
    public org.bukkit.inventory.Recipe toBukkitRecipe(final org.bukkit.NamespacedKey id) {
        return new org.bukkit.inventory.BrewingRecipe(
            id,
            org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(this.output),
            org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.input.ingredient()),
            org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.reagent.ingredient())
        );
    }
    // Paper end - Recipe API
}
