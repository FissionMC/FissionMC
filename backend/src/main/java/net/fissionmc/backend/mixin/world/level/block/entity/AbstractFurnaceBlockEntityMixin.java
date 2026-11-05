package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity.java.patch */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceBlockEntityMixin {
    @Shadow protected net.minecraft.core.NonNullList<ItemStack> items;

    // CraftBukkit start - add fields and methods
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = net.minecraft.world.item.Item.ABSOLUTE_MAX_STACK_SIZE;
    private double cookSpeedMultiplier = 1.0; // Paper - cook speed multiplier API

    public java.util.List<ItemStack> getContents() {
        return this.items;
    }

    public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.add(player);
    }

    public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.remove(player);
    }

    public java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        return this.transaction;
    }

    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }
    // CraftBukkit end

    // TODO(behaviour): load/save "Paper.CookSpeedMultiplier", FurnaceStartSmeltEvent, FurnaceExtractEvent/BlockExpEvent (awardUsedRecipesAndPopExperience / createExperience), fillStackedContents.
    // Done: FurnaceBurnEvent, FurnaceSmeltEvent (burn returns boolean again: setRecipeUsed is skipped), cookSpeedMultiplier in cook time.

    private static final ThreadLocal<org.bukkit.event.inventory.FurnaceBurnEvent> fission$burnEvent = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> fission$burned = new ThreadLocal<>();

    @Shadow private static int getTotalCookTime(RecipeHolder<? extends AbstractCookingRecipe> recipe, AbstractFurnaceBlockEntity entity) { throw new AssertionError(); }

    @ModifyReturnValue(method = "getTotalCookTime", at = @At("RETURN"))
    private static int fission$cookSpeed(final int vanilla, final RecipeHolder<? extends AbstractCookingRecipe> recipe, final AbstractFurnaceBlockEntity entity) {
        return entity.cookSpeedMultiplier != 1.0d ? (int) Math.ceil(vanilla / entity.cookSpeedMultiplier) : vanilla;
    }

    // FurnaceBurnEvent (before the new burn time is applied)
    @Inject(method = "serverTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;getSpeedMultiplier(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;)F"), cancellable = true)
    private static void fission$burnEvent(final ServerLevel level, final BlockPos pos, final net.minecraft.world.level.block.state.BlockState state, final AbstractFurnaceBlockEntity entity, final CallbackInfo ci,
                                          @Local(name = "fuel") net.minecraft.world.item.ItemStack fuel, @Local(name = "newLitTime") LocalIntRef newLitTime) {
        org.bukkit.event.inventory.FurnaceBurnEvent burnEvent = new org.bukkit.event.inventory.FurnaceBurnEvent(
            org.bukkit.craftbukkit.block.CraftBlock.at(level, pos),
            org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(fuel),
            newLitTime.get()
        );
        if (!burnEvent.callEvent()) {
            ci.cancel();
            return;
        }
        newLitTime.set(burnEvent.isBurning() ? burnEvent.getBurnTime() : 0); // CraftBukkit - respect event output
        fission$burnEvent.set(burnEvent);
    }

    // Paper - add consumeFuel to FurnaceBurnEvent
    @WrapOperation(method = "serverTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;consumeFuel(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/NonNullList;Lnet/minecraft/world/item/ItemStack;)V"))
    private static void fission$consumeFuel(final ServerLevel level, final BlockPos pos, final NonNullList<net.minecraft.world.item.ItemStack> items, final net.minecraft.world.item.ItemStack fuel, final Operation<Void> original) {
        org.bukkit.event.inventory.FurnaceBurnEvent burnEvent = fission$burnEvent.get();
        fission$burnEvent.remove();
        if (burnEvent == null || burnEvent.willConsumeFuel()) {
            original.call(level, pos, items, fuel);
        }
    }

    // CraftBukkit - burn with FurnaceSmeltEvent
    @WrapOperation(method = "serverTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;burn(Lnet/minecraft/core/NonNullList;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V"))
    private static void fission$burn(final NonNullList<net.minecraft.world.item.ItemStack> items, final net.minecraft.world.item.ItemStack inputItemStack, net.minecraft.world.item.ItemStack result, final Operation<Void> original,
                                     @Local(name = "level") final ServerLevel level, @Local(name = "pos") final BlockPos pos, @Local(name = "recipe") RecipeHolder<? extends AbstractCookingRecipe> recipe) {
        net.minecraft.world.item.ItemStack resultItemStack = items.get(2);
        org.bukkit.inventory.ItemStack apiIngredient = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(inputItemStack);
        org.bukkit.inventory.ItemStack apiResult = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(result);

        org.bukkit.event.inventory.FurnaceSmeltEvent furnaceSmeltEvent = new org.bukkit.event.inventory.FurnaceSmeltEvent(
            org.bukkit.craftbukkit.block.CraftBlock.at(level, pos),
            apiIngredient,
            apiResult,
            (org.bukkit.inventory.CookingRecipe<?>) recipe.toBukkitRecipe() // Paper - Add recipe to cook events
        );
        fission$burned.set(Boolean.FALSE);
        if (!furnaceSmeltEvent.callEvent()) return;

        apiResult = furnaceSmeltEvent.getResult();
        result = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(apiResult);

        if (!result.isEmpty()) {
            if (resultItemStack.isEmpty()) {
                items.set(2, result.copy());
            } else if (org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(resultItemStack).isSimilar(apiResult)) {
                resultItemStack.grow(result.getCount());
            } else {
                return;
            }
        }

        if (inputItemStack.is(net.minecraft.world.item.Items.WET_SPONGE) && !items.get(1).isEmpty() && items.get(1).is(net.minecraft.world.item.Items.BUCKET)) {
            items.set(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
        }

        inputItemStack.shrink(1);
        fission$burned.set(Boolean.TRUE);
    }

    @WrapOperation(method = "serverTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;setRecipeUsed(Lnet/minecraft/world/item/crafting/RecipeHolder;)V"))
    private static void fission$setRecipeUsed(final AbstractFurnaceBlockEntity entity, final RecipeHolder<?> recipe, final Operation<Void> original) {
        Boolean burned = fission$burned.get();
        fission$burned.remove();
        if (burned == null || burned) {
            original.call(entity, recipe);
        }
    }
}
