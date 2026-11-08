package net.fissionmc.backend.mixin.world.entity.monster;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: sources/net/minecraft/world/entity/monster/ElderGuardian.java.patch + sources/net/minecraft/world/effect/MobEffectUtil.java.patch
 * done: ElderGuardianAppearanceEvent (cancelled players get neither the effect nor the packet), EntityPotionEffectEvent cause ATTACK.
 * The MobEffectUtil overloads with cause/predicate are not added (only this caller needs them), the filter is re-implemented here.
 */
@Mixin(ElderGuardian.class)
public abstract class ElderGuardianMixin {
    // Paper start - Add ElderGuardianAppearanceEvent
    @WrapOperation(method = "customServerAiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/effect/MobEffectUtil;addEffectToPlayersAround(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;DLnet/minecraft/world/effect/MobEffectInstance;I)Ljava/util/List;"))
    private List<ServerPlayer> paper$appearanceEvent(final ServerLevel level, final Entity source, final Vec3 position, final double radius, final MobEffectInstance effectInstance, final int displayEffectLimit, final Operation<List<ServerPlayer>> original) {
        final net.minecraft.core.Holder<MobEffect> effect = effectInstance.getEffect();
        final List<ServerPlayer> players = level.getPlayers(input -> {
            final boolean condition = input.gameMode.isSurvival()
                && (source == null || !source.isAlliedTo(input))
                && position.closerThan(input.position(), radius)
                && (!input.hasEffect(effect)
                    || input.getEffect(effect).getAmplifier() < effectInstance.getAmplifier()
                    || input.getEffect(effect).endsWithin(displayEffectLimit - 1));
            return condition && new io.papermc.paper.event.entity.ElderGuardianAppearanceEvent((org.bukkit.entity.ElderGuardian) ((ElderGuardian) (Object) this).getBukkitEntity(), input.getBukkitEntity()).callEvent(); // test the player only after it is true
        });
        players.forEach(player -> player.addEffect(new MobEffectInstance(effectInstance), source, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.ATTACK));
        return players;
    }
    // Paper end - Add ElderGuardianAppearanceEvent
}
