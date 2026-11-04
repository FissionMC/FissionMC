package net.fissionmc.backend.mixin.world.item.crafting;

import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.TransmuteRecipe;
import net.minecraft.world.item.crafting.TransmuteResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/item/crafting/TransmuteRecipe.java.patch */
@Mixin(TransmuteRecipe.class)
public abstract class TransmuteRecipeMixin {
    @Shadow @Final private Ingredient input;
    @Shadow @Final private Ingredient material;
    @Shadow @Final private TransmuteResult result;

    // CraftBukkit start
    public org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        return new org.bukkit.craftbukkit.inventory.CraftTransmuteRecipe(
            id,
            org.bukkit.craftbukkit.inventory.CraftItemType.minecraftToBukkit(this.result.item().orElseGet(() -> this.input.values.iterator().next()).value()),
            org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.input),
            org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.material)
        );
    }
    // CraftBukkit end
}
