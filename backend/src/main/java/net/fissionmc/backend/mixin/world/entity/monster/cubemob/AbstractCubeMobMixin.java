package net.fissionmc.backend.mixin.world.entity.monster.cubemob;

import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/monster/cubemob/AbstractCubeMob.java.patch */
@Mixin(AbstractCubeMob.class)
public abstract class AbstractCubeMobMixin {
    private boolean canWander = true; // Paper - Slime pathfinder events
    // TODO(behaviour): "Paper.canWander" save/load; slime goals fire SlimePathfindEvents and respect canWander.

    // Paper start - Slime pathfinder events
    public boolean canWander() {
        return this.canWander;
    }

    public void setWander(boolean canWander) {
        this.canWander = canWander;
    }
    // Paper end - Slime pathfinder events
}
