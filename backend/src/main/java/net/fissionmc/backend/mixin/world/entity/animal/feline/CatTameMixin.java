package net.fissionmc.backend.mixin.world.entity.animal.feline;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.fissionmc.backend.support.TameEvents;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: EntityTameEvent for Cat (tryToTame). */
@Mixin(Cat.class)
public abstract class CatTameMixin {
    @ModifyExpressionValue(method = "tryToTame", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt(I)I"))
    private int paper$tameEvent(final int roll, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) final Player player) {
        return TameEvents.roll(roll, (net.minecraft.world.entity.Mob) (Object) this, player);
    }
}
