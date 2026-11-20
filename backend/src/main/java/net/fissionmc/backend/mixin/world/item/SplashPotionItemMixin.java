package net.fissionmc.backend.mixin.world.item;

import net.fissionmc.backend.support.ProjectileLaunch;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/SplashPotionItem.java.patch + ThrowablePotionItem.java.patch (PlayerLaunchProjectileEvent; use runs the throw first, then plays the sound). */
@Mixin(SplashPotionItem.class)
public abstract class SplashPotionItemMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void paper$use(final Level level, final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir) {
        final InteractionResult wrapper = ProjectileLaunch.throwPotion((SplashPotionItem) (Object) this, level, player, hand, ThrownSplashPotion::new);
        if (wrapper instanceof InteractionResult.Fail) {
            cir.setReturnValue(wrapper);
            return;
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SPLASH_POTION_THROW, SoundSource.PLAYERS, 0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
        cir.setReturnValue(wrapper);
    }
}
