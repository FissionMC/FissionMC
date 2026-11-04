package net.fissionmc.backend.mixin.world.food;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodConstants;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/food/FoodData.java.patch */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @Shadow private int foodLevel;

    @Shadow private void add(final int food, final float saturation) { throw new AssertionError(); }

    // CraftBukkit start
    // Surface: public fields. Mixin requires private fields; the loader widens them.
    // TODO(behaviour): tick() uses these rates, fires FoodLevelChangeEvent and regain/exhaustion reasons
    private int saturatedRegenRate = FoodConstants.HEALTH_TICK_COUNT_SATURATED;
    private int unsaturatedRegenRate = FoodConstants.HEALTH_TICK_COUNT;
    private int starvationRate = FoodConstants.HEALTH_TICK_COUNT;
    // CraftBukkit end

    // CraftBukkit start
    public void eat(FoodProperties foodProperties, net.minecraft.world.item.ItemStack stack, ServerPlayer serverPlayer) {
        int oldFoodLevel = this.foodLevel;
        org.bukkit.event.entity.FoodLevelChangeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callFoodLevelChangeEvent(serverPlayer, foodProperties.nutrition() + oldFoodLevel, stack);
        if (!event.isCancelled()) {
            this.add(event.getFoodLevel() - oldFoodLevel, foodProperties.saturation());
        }
        serverPlayer.getBukkitEntity().sendHealthUpdate();
    }
    // CraftBukkit end
}
