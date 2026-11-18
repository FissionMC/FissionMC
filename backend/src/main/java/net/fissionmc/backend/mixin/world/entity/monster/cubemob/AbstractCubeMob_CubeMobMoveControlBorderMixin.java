package net.fissionmc.backend.mixin.world.entity.monster.cubemob;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fissionmc.backend.relay.RegionsBackend;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FissionMC static regions, mob AI border: slimes, magma cubes and sulfur cubes hop along a yaw (setDirection), they never use a path, a random position or a wanted position, so the other
 * border mixins do not see them. At the moment of the hop the landing block (about one hop along the yaw) is checked: if the mob may not go there, the target yaw is turned (180, then 90, -90)
 * and this hop is skipped; an attacker only hops while it may go to its target. Also covers a freshly handed-over copy, whose random-direction goal picks a new yaw at once. New file.
 */
@Mixin(targets = "net.minecraft.world.entity.monster.cubemob.AbstractCubeMob$CubeMobMoveControl")
public abstract class AbstractCubeMob_CubeMobMoveControlBorderMixin extends MoveControl<AbstractCubeMob> {
    private AbstractCubeMob_CubeMobMoveControlBorderMixin(final AbstractCubeMob mob) { super(mob); }   // never called (mixin class)

    @Shadow private float yRot;

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/control/JumpControl;jump()V"))
    private void fission$noHopAcrossTheBorder(final JumpControl jump, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        if (RegionsBackend.mobAiBorderActive()) {
            // a skipped hop must not leave the push of this tick behind: the move control has just set zza = speed, and the mob would slide along its yaw over the border like a hop
            final Float safe = RegionsBackend.cubeHopYaw(this.mob, this.mob.getSize(), this.mob.getYRot());
            if (safe == null) {
                this.yRot = Mth.wrapDegrees(this.yRot + 180.0F);   // no allowed direction (or an attacker whose target is across): turn around and skip this hop
                this.mob.setSpeed(0.0F); this.mob.setXxa(0.0F); this.mob.setZza(0.0F);
                return;
            }
            if (Math.abs(Mth.wrapDegrees(safe - this.mob.getYRot())) > 0.5F) {
                this.yRot = safe;                                  // aim the next hops along the allowed direction and skip this one
                this.mob.setSpeed(0.0F); this.mob.setXxa(0.0F); this.mob.setZza(0.0F);
                return;
            }
        }
        original.call(jump);
    }
}
