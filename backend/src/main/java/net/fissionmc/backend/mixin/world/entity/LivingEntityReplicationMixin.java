package net.fissionmc.backend.mixin.world.entity;

import net.fissionmc.backend.replication.Replication;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FissionMC replication: reports deaths of non-player entities on a ServerLevel (thin shim, logic is Kotlin). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityReplicationMixin {
    @Inject(method = "die", at = @At("HEAD"))
    private void fission$replicateDeath(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level() instanceof ServerLevel && !self.isRemoved()) {
            Replication.onEntityDied(self, source.type().msgId());
        }
    }
}
