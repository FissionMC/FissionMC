package net.fissionmc.backend.mixin.world.entity.animal.turtle;

import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.animal.turtle.Turtle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/animal/turtle/Turtle.java.patch (TurtleBreedGoal.breed: EntityFertilizeEggEvent) */
@Mixin(targets = "net.minecraft.world.entity.animal.turtle.Turtle$TurtleBreedGoal")
public abstract class Turtle_TurtleBreedGoalMixin extends BreedGoal {
    private Turtle_TurtleBreedGoalMixin() { super(null, 0.0); }

    @Shadow @Final private Turtle turtle;

    @Inject(method = "breed", at = @At("HEAD"), cancellable = true)
    private void paper$fertilizeEvent(final CallbackInfo ci) {
        // Paper start - Add EntityFertilizeEggEvent event
        io.papermc.paper.event.entity.EntityFertilizeEggEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityFertilizeEggEvent(this.animal, this.partner);
        if (event.isCancelled()) {
            ci.cancel();
        }
        // Paper end - Add EntityFertilizeEggEvent event
    }
}
