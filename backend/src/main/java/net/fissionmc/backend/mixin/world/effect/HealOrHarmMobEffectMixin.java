package net.fissionmc.backend.mixin.world.effect;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/effect/HealOrHarmMobEffect.java.patch (EntityEffectTickEvent, EntityRegainHealthEvent reason MAGIC). */
@Mixin(targets = "net.minecraft.world.effect.HealOrHarmMobEffect")
public abstract class HealOrHarmMobEffectMixin {
    @Inject(method = "applyInstantaneousEffect", at = @At("HEAD"), cancellable = true)
    private void fission$effectTickEvent(final ServerLevel level, final Entity source, final Entity indirectSource, final LivingEntity mob, final int amplification, final double scale, final CallbackInfo ci) {
        if (!new io.papermc.paper.event.entity.EntityEffectTickEvent(mob.getBukkitEntity(), org.bukkit.craftbukkit.potion.CraftPotionEffectType.minecraftToBukkit((net.minecraft.world.effect.MobEffect) (Object) this), amplification).callEvent()) { ci.cancel(); } // Paper - Add EntityEffectTickEvent
    }

    @WrapOperation(method = {"applyEffectTick", "applyInstantaneousEffect"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;heal(F)V"))
    private void fission$healReason(final LivingEntity mob, final float amount, final Operation<Void> original) {
        mob.heal(amount, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.MAGIC); // CraftBukkit
    }
}
