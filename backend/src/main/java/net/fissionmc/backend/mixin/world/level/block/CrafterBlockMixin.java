package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.fissionmc.backend.blocks.PaperGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.level.block.CrafterBlock;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.block.CrafterCraftEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: CrafterBlock.java.patch - CrafterCraftEvent after the recipe result is assembled. Paper returns from dispenseFrom when the
 * event is cancelled; here the result becomes EMPTY and the "results empty" fail level event is suppressed (same observable effect).
 * TODO(behaviour): the InventoryMoveItemEvent hunk of dispenseItem is not ported.
 */
@Mixin(CrafterBlock.class)
public abstract class CrafterBlockMixin {
    @WrapOperation(method = "dispenseFrom", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/crafting/CraftingRecipe;assemble(Lnet/minecraft/world/item/crafting/RecipeInput;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack paper$crafterCraftEvent(final CraftingRecipe recipe, final RecipeInput input, final Operation<ItemStack> original,
                                              @Local(argsOnly = true) final ServerLevel level, @Local(argsOnly = true) final BlockPos pos, @Local final RecipeHolder<CraftingRecipe> pickedRecipe) {
        final ItemStack results = original.call(recipe, input);
        // CraftBukkit start
        final CrafterCraftEvent event = CraftEventFactory.callCrafterCraftEvent(pos, level, results, pickedRecipe);
        if (event.isCancelled()) {
            PaperGrowth.crafterCancelled = true;
            return ItemStack.EMPTY;
        }
        return CraftItemStack.asNMSCopy(event.getResult());
        // CraftBukkit end
    }

    @WrapOperation(method = "dispenseFrom", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;levelEvent(ILnet/minecraft/core/BlockPos;I)V", ordinal = 1))
    private void paper$silentWhenCancelled(final ServerLevel level, final int type, final BlockPos pos, final int data, final Operation<Void> original) {
        if (PaperGrowth.crafterCancelled) {
            PaperGrowth.crafterCancelled = false;
            return;
        }
        original.call(level, type, pos, data);
    }
}
