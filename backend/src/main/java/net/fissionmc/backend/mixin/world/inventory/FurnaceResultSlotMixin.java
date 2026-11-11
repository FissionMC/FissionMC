package net.fissionmc.backend.mixin.world.inventory;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fissionmc.backend.blocks.PaperContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: sources/net/minecraft/world/inventory/FurnaceResultSlot.java.patch
 * done: awardUsedRecipesAndPopExperience(player, carried, removeCount) (the extra arguments travel in PaperContext.FURNACE_EXTRACT: FurnaceExtractEvent).
 */
@Mixin(FurnaceResultSlot.class)
public abstract class FurnaceResultSlotMixin {
    @Shadow private int removeCount;

    @WrapOperation(method = "checkTakeAchievements", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;awardUsedRecipesAndPopExperience(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void paper$extractContext(final AbstractFurnaceBlockEntity furnace, final ServerPlayer player, final Operation<Void> original, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) final ItemStack carried) {
        PaperContext.FURNACE_EXTRACT.set(new PaperContext.FurnaceExtract(player, carried, this.removeCount)); // CraftBukkit
        try {
            original.call(furnace, player);
        } finally {
            PaperContext.FURNACE_EXTRACT.remove();
        }
    }
}
