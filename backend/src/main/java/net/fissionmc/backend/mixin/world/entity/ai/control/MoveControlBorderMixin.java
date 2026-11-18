package net.fissionmc.backend.mixin.world.entity.ai.control;

import net.fissionmc.backend.relay.RegionsBackend;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FissionMC static regions, mob AI border: goals that steer by a wanted position without a path (ghast, vex, phantom, bee, flying and swimming mobs) are refused a wanted position on
 * another backend's side (not while a navigation path is in progress: that was vetted by [PathNavigationBorderMixin]). The mob keeps its previous wanted position. New file.
 */
@Mixin(MoveControl.class)
public abstract class MoveControlBorderMixin<T extends Mob> {
    @Shadow @Final protected T mob;

    @Inject(method = "setWantedPosition(DDDD)V", at = @At("HEAD"), cancellable = true)
    private void fission$noWantedPositionAcrossTheBorder(final double x, final double y, final double z, final double speed, final CallbackInfo ci) {
        if (RegionsBackend.mobAiBorderActive() && !RegionsBackend.mobMayTarget(this.mob, Mth.floor(x), Mth.floor(z)) && !this.mob.getNavigation().isInProgress()) ci.cancel();   // a navigation in progress was vetted when its path was made
    }
}
