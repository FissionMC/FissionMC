package net.fissionmc.backend.mixin.world.level.block.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/CampfireBlockEntity.java.patch
 * done: stopCooking (cookTick, "Paper.StopCooking" load/save), BlockCookEvent with recipe, campfire drop locations, CampfireStartEvent.
 */
@Mixin(CampfireBlockEntity.class)
public abstract class CampfireBlockEntityMixin {
    @Shadow @Final private net.minecraft.core.NonNullList<ItemStack> items;
    @Shadow @Final public int[] cookingProgress;

    // Surface: public final (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private final boolean[] stopCooking = new boolean[4]; // Paper - Add more Campfire API

    // Paper start - Add more Campfire API: vanilla has just done cookingProgress[slot]++, undone for a stopped slot
    @Inject(method = "cookTick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/block/entity/CampfireBlockEntity;cookingProgress:[I", opcode = org.objectweb.asm.Opcodes.GETFIELD, ordinal = 1))
    private static void paper$stopCooking(final ServerLevel level, final BlockPos pos, final BlockState state, final CampfireBlockEntity entity, final RecipeManager.CachedCheck<SingleRecipeInput, CampfireCookingRecipe> recipeCache, final CallbackInfo ci, @Local(ordinal = 0) final int slot) {
        if (entity.stopCooking[slot]) {
            entity.cookingProgress[slot]--;
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void paper$loadStopCooking(final net.minecraft.world.level.storage.ValueInput input, final CallbackInfo ci) {
        input.read("Paper.StopCooking", com.mojang.serialization.Codec.BYTE_BUFFER).ifPresent(bytes -> {
            final boolean[] cookingState = new boolean[4];
            for (int index = 0; bytes.hasRemaining() && index < cookingState.length; index++) {
                cookingState[index] = bytes.get() == 1;
            }
            System.arraycopy(cookingState, 0, this.stopCooking, 0, Math.min(this.stopCooking.length, bytes.capacity()));
        });
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void paper$saveStopCooking(final net.minecraft.world.level.storage.ValueOutput output, final CallbackInfo ci) {
        byte[] cookingState = new byte[4];
        for (int index = 0; index < cookingState.length; index++) {
            cookingState[index] = (byte) (this.stopCooking[index] ? 1 : 0);
        }
        output.store("Paper.StopCooking", com.mojang.serialization.Codec.BYTE_BUFFER, java.nio.ByteBuffer.wrap(cookingState));
    }
    // Paper end - Add more Campfire API

    // CraftBukkit start - fire BlockCookEvent (a cancelled event ends cookTick: the inject right after the wrapped call returns)
    private static final ThreadLocal<Boolean> paper$cookCancelled = new ThreadLocal<>();

    @WrapOperation(method = "cookTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/Containers;dropItemStack(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V"))
    private static void paper$cookEvent(final Level levelArg, final double x, final double y, final double z, ItemStack result, final Operation<Void> original,
                                        @Local(argsOnly = true) final ServerLevel level, @Local(argsOnly = true) final BlockPos pos, @Local(argsOnly = true) final CampfireBlockEntity entity,
                                        @Local(argsOnly = true) final RecipeManager.CachedCheck<SingleRecipeInput, CampfireCookingRecipe> recipeCache, @Local(ordinal = 0) final int slot) {
        final ItemStack itemStack = ((CampfireBlockEntityMixin) (Object) entity).items.get(slot);
        final java.util.Optional<RecipeHolder<CampfireCookingRecipe>> recipe = recipeCache.getRecipeFor(new SingleRecipeInput(itemStack), level); // Paper - add recipe to cook events (cached check: same holder as the one vanilla just used)
        org.bukkit.inventory.ItemStack source = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(itemStack);
        org.bukkit.inventory.ItemStack apiResult = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(result);

        org.bukkit.event.block.BlockCookEvent blockCookEvent = new org.bukkit.event.block.BlockCookEvent(
            org.bukkit.craftbukkit.block.CraftBlock.at(level, pos),
            source,
            apiResult,
            (org.bukkit.inventory.CookingRecipe<?>) recipe.map(RecipeHolder::toBukkitRecipe).orElse(null) // Paper - Add recipe to cook events
        );

        if (!blockCookEvent.callEvent()) {
            paper$cookCancelled.set(Boolean.TRUE);
            return;
        }

        apiResult = blockCookEvent.getResult();
        result = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(apiResult);
        // CraftBukkit end
        // Paper start - Fix item locations dropped from campfires
        double deviation = 0.05F * RandomSource.GAUSSIAN_SPREAD_FACTOR;
        while (!result.isEmpty()) {
            net.minecraft.world.entity.item.ItemEntity droppedItem = new net.minecraft.world.entity.item.ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, result.split(level.getRandom().nextInt(21) + 10));
            droppedItem.setDeltaMovement(level.getRandom().triangle(0.0, deviation), level.getRandom().triangle(0.2, deviation), level.getRandom().triangle(0.0, deviation));
            level.addFreshEntity(droppedItem);
        }
        // Paper end - Fix item locations dropped from campfires
    }

    @Inject(method = "cookTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/Containers;dropItemStack(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V", shift = At.Shift.AFTER), cancellable = true)
    private static void paper$cookCancelled(final CallbackInfo ci) {
        if (paper$cookCancelled.get() != null) {
            paper$cookCancelled.remove();
            ci.cancel();
        }
    }

    // CraftBukkit start - CampfireStartEvent
    @WrapOperation(method = "placeFood", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/crafting/CampfireCookingRecipe;cookingTime()I"))
    private int paper$startEvent(final CampfireCookingRecipe instance, final Operation<Integer> original, @Local(argsOnly = true) final ItemStack placeItem, @Local final java.util.Optional<RecipeHolder<CampfireCookingRecipe>> recipe) {
        final net.minecraft.world.level.block.entity.BlockEntity self = (net.minecraft.world.level.block.entity.BlockEntity) (Object) this;
        org.bukkit.event.block.CampfireStartEvent event = new org.bukkit.event.block.CampfireStartEvent(
            org.bukkit.craftbukkit.block.CraftBlock.at(self.getLevel(), self.getBlockPos()),
            org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(placeItem),
            (org.bukkit.inventory.CampfireRecipe) recipe.get().toBukkitRecipe()
        );
        event.callEvent();
        return event.getTotalCookTime(); // recipe.get().value().cookingTime() -> event.getTotalCookTime()
    }
    // CraftBukkit end
}
