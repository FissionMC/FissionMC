package net.fissionmc.backend.mixin.world.entity.monster.skeleton;

import net.minecraft.world.entity.monster.skeleton.Skeleton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/monster/skeleton/Skeleton.java.patch (done: FROZEN spawn and transform reasons). */
@Mixin(Skeleton.class)
public abstract class SkeletonMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void paper$trackerReasons(final CallbackInfo ci) {
        ((Skeleton) (Object) this).freezingTracker.setEventReasons(org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.FROZEN, org.bukkit.event.entity.EntityTransformEvent.TransformReason.FROZEN); // Paper - add spawn and transform reasons
    }
}
