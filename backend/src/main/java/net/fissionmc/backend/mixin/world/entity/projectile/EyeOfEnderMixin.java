package net.fissionmc.backend.mixin.world.entity.projectile;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/projectile/EyeOfEnder.java.patch */
@Mixin(EyeOfEnder.class)
public abstract class EyeOfEnderMixin {
    @Shadow private Vec3 target;
    @Shadow private int life;
    @Shadow private boolean surviveAfterDeath;

    @Inject(method = "signalTo(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void fission$signalToDelegate(Vec3 target, CallbackInfo ci) {
        ci.cancel();
        this.signalTo(target, true);
    }

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

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/EyeOfEnder;discard()V"))
    private void fission$discard(EyeOfEnder instance) {
        instance.discard(this.surviveAfterDeath ? org.bukkit.event.entity.EntityRemoveEvent.Cause.DROP : org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
    }
}
