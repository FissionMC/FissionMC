package net.fissionmc.backend.mixin.world.entity.animal.turtle;

import net.minecraft.world.entity.animal.turtle.Turtle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/animal/turtle/Turtle.java.patch. Only a bridge to the private setLayingEgg for the goal mixins (goals are not nest mates of the mixin classes). */
@Mixin(Turtle.class)
public abstract class TurtleMixin {
    @Shadow private void setLayingEgg(boolean layingEgg) { throw new AssertionError(); }

    public void fission$setLayingEgg(boolean layingEgg) {
        this.setLayingEgg(layingEgg);
    }
}
