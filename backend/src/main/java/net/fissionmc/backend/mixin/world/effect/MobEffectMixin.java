package net.fissionmc.backend.mixin.world.effect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/effect/MobEffect.java.patch (EntityEffectTickEvent for instantaneous effects). */
@Mixin(MobEffect.class)
public abstract class MobEffectMixin {
    @Inject(method = "applyInstantaneousEffect", at = @At("HEAD"), cancellable = true)
    private void fission$effectTickEvent(final ServerLevel level, final Entity source, final Entity indirectSource, final LivingEntity mob, final int amplification, final double scale, final CallbackInfo ci) {
        if (!new io.papermc.paper.event.entity.EntityEffectTickEvent(mob.getBukkitEntity(), org.bukkit.craftbukkit.potion.CraftPotionEffectType.minecraftToBukkit((MobEffect) (Object) this), amplification).callEvent()) { ci.cancel(); } // Paper - Add EntityEffectTickEvent
    }
}
