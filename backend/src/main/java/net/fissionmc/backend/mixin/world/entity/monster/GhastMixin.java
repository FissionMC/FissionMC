package net.fissionmc.backend.mixin.world.entity.monster;

import net.minecraft.world.entity.monster.Ghast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/monster/Ghast.java.patch */
@Mixin(Ghast.class)
public abstract class GhastMixin {
    @Shadow private int explosionPower;

    // Paper start
    public void setExplosionPower(int explosionPower) {
        this.explosionPower = explosionPower;
    }
    // Paper end
}
