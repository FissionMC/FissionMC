package net.fissionmc.backend.mixin.world.entity.projectile;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/projectile/EyeOfEnder.java.patch */
@Mixin(EyeOfEnder.class)
public abstract class EyeOfEnderMixin {
    @Shadow private Vec3 target;
    @Shadow private int life;
    @Shadow private boolean surviveAfterDeath;

    // Paper start - Change EyeOfEnder target without changing other things
    public void signalTo(final Vec3 target, final boolean update) {
        Entity self = (Entity) (Object) this;
        Vec3 delta = target.subtract(self.position());
        double horizontalDistance = delta.horizontalDistance();
        if (horizontalDistance > 12.0) {
            this.target = self.position().add(delta.x / horizontalDistance * 12.0, 8.0, delta.z / horizontalDistance * 12.0);
        } else {
            this.target = target;
        }

        if (update) { // Paper - Change EyeOfEnder target without changing other things
        this.life = 0;
        this.surviveAfterDeath = self.getRandom().nextInt(5) > 0;
        } // Paper - Change EyeOfEnder target without changing other things
    }
    // Paper end - Change EyeOfEnder target without changing other things
}
