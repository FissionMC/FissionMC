package net.fissionmc.backend.mixin.world.entity.player;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity area (LivingEntity damage pipeline): Paper's Player#actuallyHurt(level, source, dmg, event) is
 * {@code return super.actuallyHurt(level, source, dmg, event);} (paper-server/patches/sources/net/minecraft/world/entity/player/Player.java.patch),
 * i.e. the vanilla Player override is skipped and LivingEntity's CraftBukkit body (which contains the player-specific exhaustion/stats) runs.
 */
@Mixin(Player.class)
public abstract class Player_entityMixin {
    @Inject(method = "actuallyHurt", at = @At("HEAD"), cancellable = true)
    private void fission$paperActuallyHurt(ServerLevel level, DamageSource source, float dmg, CallbackInfo ci) {
        if (((net.fissionmc.backend.world.entity.LivingEntityDuck) this).fission$paperActuallyHurtPending(level, source, dmg)) {
            ci.cancel();
        }
    }
}
