package net.fissionmc.backend.mixin.world.entity.projectile.arrow;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Paper: sources/net/minecraft/world/entity/projectile/arrow/SpectralArrow.java.patch */
@Mixin(SpectralArrow.class)
public abstract class SpectralArrowMixin {
    @Redirect(method = "doPostHurtEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean fission$arrowCause(LivingEntity mob, MobEffectInstance effect, Entity effectSource) {
        return mob.addEffect(effect, effectSource, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.ARROW); // CraftBukkit
    }
}
