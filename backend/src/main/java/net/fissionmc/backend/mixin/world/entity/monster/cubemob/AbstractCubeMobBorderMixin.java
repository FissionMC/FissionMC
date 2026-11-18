package net.fissionmc.backend.mixin.world.entity.monster.cubemob;

import net.fissionmc.backend.relay.RegionsBackend;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FissionMC static regions, mob AI border: movement-level guard for slimes, magma cubes and sulfur cubes (see the move control mixin: a hop is only vetted when it starts, a cube mob also slides on
 * the push of its move control and on landing speed). At the end of every tick the horizontal velocity that would carry the mob onto a block it may not go to is removed, so it never leaves its
 * side by its own movement; it slides along the border instead. New file.
 */
@Mixin(AbstractCubeMob.class)
public abstract class AbstractCubeMobBorderMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void fission$stayOnTheOwnSide(final CallbackInfo ci) {
        if (!RegionsBackend.mobAiBorderActive()) return;
        final AbstractCubeMob self = (AbstractCubeMob) (Object) this;
        final Vec3 v = self.getDeltaMovement();
        final double[] step = RegionsBackend.cubeStep(self, v.x, v.z);
        if (step != null) self.setDeltaMovement(step[0], v.y, step[1]);
    }
}
