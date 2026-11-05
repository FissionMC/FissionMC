package net.fissionmc.backend.mixin.world.food;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodConstants;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Paper: sources/net/minecraft/world/food/FoodData.java.patch */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @Shadow private int foodLevel;
    @Shadow private float saturationLevel;

    @Shadow private void add(final int food, final float saturation) { throw new AssertionError(); }

    // CraftBukkit start
    // Surface: public fields. Mixin requires private fields; the loader widens them.
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

    // Hunger: FoodLevelChangeEvent (CraftBukkit)
    @WrapOperation(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/food/FoodData;foodLevel:I", opcode = Opcodes.PUTFIELD))
    private void craftbukkit$hungerEvent(final FoodData self, final int newFoodLevel, final Operation<Void> original, @Local(argsOnly = true) final ServerPlayer player) {
        // CraftBukkit start
        org.bukkit.event.entity.FoodLevelChangeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callFoodLevelChangeEvent(player, newFoodLevel);

        if (!event.isCancelled()) {
            original.call(self, event.getFoodLevel());
        }

        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHealthPacket(player.getBukkitEntity().getScaledHealth(), this.foodLevel, this.saturationLevel));
        // CraftBukkit end
    }

    // CraftBukkit - added RegainReason (fast regen when saturated)
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;heal(F)V", ordinal = 0))
    private void paper$healSaturated(final ServerPlayer player, final float amount, final Operation<Void> original) {
        player.heal(amount, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.SATIATED, true); // CraftBukkit - added RegainReason // Paper - This is fast regen
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;heal(F)V", ordinal = 1))
    private void craftbukkit$healSatiated(final ServerPlayer player, final float amount, final Operation<Void> original) {
        player.heal(amount, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.SATIATED); // CraftBukkit - added RegainReason
    }

    // CraftBukkit - EntityExhaustionEvent
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;addExhaustion(F)V", ordinal = 0))
    private void craftbukkit$regenExhaustionSaturated(final FoodData self, final float amount, final Operation<Void> original, @Local(argsOnly = true) final ServerPlayer player) {
        player.causeFoodExhaustion(amount, org.bukkit.event.entity.EntityExhaustionEvent.ExhaustionReason.REGEN); // CraftBukkit - EntityExhaustionEvent
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;addExhaustion(F)V", ordinal = 1))
    private void craftbukkit$regenExhaustion(final FoodData self, final float amount, final Operation<Void> original, @Local(argsOnly = true) final ServerPlayer player) {
        player.causeFoodExhaustion(player.level().spigotConfig.regenExhaustion, org.bukkit.event.entity.EntityExhaustionEvent.ExhaustionReason.REGEN); // CraftBukkit - EntityExhaustionEvent // Spigot - Change to use configurable value
    }

    // CraftBukkit - add regen rate manipulation
    @ModifyConstant(method = "tick", constant = @Constant(intValue = FoodConstants.HEALTH_TICK_COUNT_SATURATED))
    private int craftbukkit$saturatedRegenRate(final int vanilla) {
        return this.saturatedRegenRate;
    }

    @ModifyConstant(method = "tick", constant = @Constant(intValue = FoodConstants.HEALTH_TICK_COUNT, ordinal = 0))
    private int craftbukkit$unsaturatedRegenRate(final int vanilla) {
        return this.unsaturatedRegenRate;
    }

    @ModifyConstant(method = "tick", constant = @Constant(intValue = FoodConstants.HEALTH_TICK_COUNT, ordinal = 1))
    private int craftbukkit$starvationRate(final int vanilla) {
        return this.starvationRate;
    }
}
