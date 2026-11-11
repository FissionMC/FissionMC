package net.fissionmc.backend.mixin.world.entity.animal.turtle;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.animal.turtle.Turtle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/entity/animal/turtle/Turtle.java.patch (TurtleGoHomeGoal.canUse: TurtleGoHomeEvent).
 * Not ported: EntityFertilizeEggEvent in TurtleBreedGoal.breed, lightning damager. */
@Mixin(targets = "net.minecraft.world.entity.animal.turtle.Turtle$TurtleGoHomeGoal")
public abstract class Turtle_TurtleGoHomeGoalMixin {
    @Shadow @Final private Turtle turtle;

    // Paper - Turtle API (the event is the last operand of the condition: only asked when the vanilla checks passed)
    @ModifyReturnValue(method = "canUse", at = @At("RETURN"))
    private boolean fission$goHomeEvent(boolean canUse) {
        return canUse && new com.destroystokyo.paper.event.entity.TurtleGoHomeEvent((org.bukkit.entity.Turtle) this.turtle.getBukkitEntity()).callEvent();
    }
}
