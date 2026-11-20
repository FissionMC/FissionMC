package net.fissionmc.backend.mixin.world.entity.animal.parrot;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.fissionmc.backend.support.TameEvents;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: EntityTameEvent for Parrot (mobInteract(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;). */
@Mixin(Parrot.class)
public abstract class ParrotTameMixin {
    @ModifyExpressionValue(method = "mobInteract(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt(I)I"))
    private int paper$tameEvent(final int roll, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) final Player player) {
        return TameEvents.roll(roll, (net.minecraft.world.entity.Mob) (Object) this, player);
    }
}
