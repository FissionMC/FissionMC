package net.fissionmc.backend.mixin.world.entity.boss.wither;

import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/boss/wither/WitherBoss.java.patch */
@Mixin(WitherBoss.class)
public abstract class WitherBossMixin {
    private boolean canPortal = false; // Paper

    @Inject(method = "canUsePortal", at = @At("HEAD"), cancellable = true)
    private void fission$canUsePortal(boolean ignorePassenger, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.canPortal); // Paper
    }

    // Paper start
    public void setCanTravelThroughPortals(boolean canPortal) {
        this.canPortal = canPortal;
    }
    // Paper end
}
