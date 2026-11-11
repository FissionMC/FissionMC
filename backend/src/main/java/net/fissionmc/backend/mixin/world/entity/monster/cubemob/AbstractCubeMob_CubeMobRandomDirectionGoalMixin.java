package net.fissionmc.backend.mixin.world.entity.monster.cubemob;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/entity/monster/cubemob/AbstractCubeMob.java.patch (Slime pathfinder events) */
@Mixin(targets = "net.minecraft.world.entity.monster.cubemob.AbstractCubeMob$CubeMobRandomDirectionGoal")
public abstract class AbstractCubeMob_CubeMobRandomDirectionGoalMixin {
    @Shadow @Final private AbstractCubeMob cubeMob;
    @Shadow private float chosenDegrees;

    @ModifyReturnValue(method = "canUse", at = @At("RETURN"))
    private boolean paper$canWander(final boolean canUse) {
        return canUse && this.cubeMob.canWander(); // Paper - Slime pathfinder events
    }

    // Paper start - Slime pathfinder events
    @org.spongepowered.asm.mixin.injection.Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/monster/cubemob/AbstractCubeMob$CubeMobRandomDirectionGoal;chosenDegrees:F", opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER), cancellable = true)
    private void paper$changeDirection(final org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        com.destroystokyo.paper.event.entity.SlimeChangeDirectionEvent event = new com.destroystokyo.paper.event.entity.SlimeChangeDirectionEvent((org.bukkit.entity.AbstractCubeMob) this.cubeMob.getBukkitEntity(), this.chosenDegrees);
        if (!this.cubeMob.canWander() || !event.callEvent()) {
            ci.cancel();
            return;
        }
        this.chosenDegrees = event.getNewYaw();
    }
    // Paper end - Slime pathfinder events
}
