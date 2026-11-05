package net.fissionmc.backend.mixin.world.food;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/food/FoodProperties.java.patch */
@Mixin(FoodProperties.class)
public abstract class FoodPropertiesMixin {
    // CraftBukkit: eating fires FoodLevelChangeEvent through the 3-arg FoodData#eat
    @WrapOperation(method = "onConsume", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;eat(Lnet/minecraft/world/food/FoodProperties;)V"))
    private void craftbukkit$eatEvent(final FoodData foodData, final FoodProperties properties, final Operation<Void> original,
                                      @Local(argsOnly = true) final ItemStack stack, @Local(argsOnly = true) final LivingEntity user) {
        foodData.eat(properties, stack, (net.minecraft.server.level.ServerPlayer) user); // CraftBukkit
    }
}
