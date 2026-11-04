package net.fissionmc.backend.mixin.world.item.crafting;

import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/item/crafting/StonecutterRecipe.java.patch */
@Mixin(StonecutterRecipe.class)
public abstract class StonecutterRecipeMixin extends SingleItemRecipe {
    private StonecutterRecipeMixin() {
        super(null, null, null);
    }

    // CraftBukkit start
    public org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        org.bukkit.inventory.ItemStack result = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(this.result());

        return new org.bukkit.craftbukkit.inventory.CraftStonecuttingRecipe(id, result, org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.input()));
    }
    // CraftBukkit end
}
