package net.fissionmc.backend.mixin.world.entity.animal.rabbit;

import net.fissionmc.backend.relay.RegionsBackend;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FissionMC static regions, mob AI border: movement-level guard for rabbits (their hop has a forward impulse of its own, so a hop towards a path node on the border overshoots it). At the end of
 * every aiStep the horizontal velocity that would carry the mob onto a block it may not go to is removed; same guard as the cube mobs. New file.
 */
@Mixin(Rabbit.class)
public abstract class RabbitBorderMixin {
    @Inject(method = "aiStep", at = @At("TAIL"))
    private void fission$stayOnTheOwnSide(final CallbackInfo ci) {
        if (!RegionsBackend.mobAiBorderActive()) return;
        final Rabbit self = (Rabbit) (Object) this;
        final Vec3 v = self.getDeltaMovement();
        final double[] step = RegionsBackend.cubeStep(self, v.x, v.z);
        if (step != null) self.setDeltaMovement(step[0], v.y, step[1]);
    }
}
