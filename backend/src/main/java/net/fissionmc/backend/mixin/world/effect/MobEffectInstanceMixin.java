package net.fissionmc.backend.mixin.world.effect;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/effect/MobEffectInstance.java.patch (EntityEffectTickEvent in tickServer). Not ported: the MC-259832 hidden effect fix, codec depth tracking. */
@Mixin(MobEffectInstance.class)
public abstract class MobEffectInstanceMixin {
    @Shadow @Final private Holder<MobEffect> effect;
    @Shadow private int amplifier;

    @ModifyExpressionValue(method = "tickServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/effect/MobEffect;shouldApplyEffectTickThisTick(II)Z"))
    private boolean fission$effectTickEvent(final boolean shouldApply, @Local(argsOnly = true) final LivingEntity target) {
        return shouldApply && new io.papermc.paper.event.entity.EntityEffectTickEvent(target.getBukkitEntity(), org.bukkit.craftbukkit.potion.CraftPotionEffectType.minecraftHolderToBukkit(this.effect), this.amplifier).callEvent(); // Paper - Add EntityEffectTickEvent
    }
}
