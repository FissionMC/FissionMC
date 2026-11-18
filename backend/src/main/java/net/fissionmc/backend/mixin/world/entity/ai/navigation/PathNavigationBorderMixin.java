package net.fissionmc.backend.mixin.world.entity.ai.navigation;

import java.util.Set;
import net.fissionmc.backend.relay.RegionsBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FissionMC static regions, mob AI border: all createPath / moveTo variants (position, entity, block set, stream) end in this one method. No path when every target lies on another
 * backend's side (no A* is run), and a path whose chosen end, or any node on the way, lies there is dropped. New file.
 */
@Mixin(PathNavigation.class)
public abstract class PathNavigationBorderMixin {
    @Shadow @Final protected Mob mob;

    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"), cancellable = true)
    private void fission$noPathAcrossTheBorder(final Set<BlockPos> targets, final int reach, final boolean above, final int accuracy, final float maxPathLength, final CallbackInfoReturnable<Path> cir) {
        if (!RegionsBackend.mobAiBorderActive() || targets.isEmpty()) return;
        for (final BlockPos target : targets) {
            if (RegionsBackend.mobMayGoTo(this.mob, target.getX(), target.getZ())) return;   // one reachable target is enough, the path may still choose another: checked on return
        }
        cir.setReturnValue(null);
    }

    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("RETURN"), cancellable = true)
    private void fission$dropPathEndingAcrossTheBorder(final Set<BlockPos> targets, final int reach, final boolean above, final int accuracy, final float maxPathLength, final CallbackInfoReturnable<Path> cir) {
        if (!RegionsBackend.mobAiBorderActive()) return;
        final Path path = cir.getReturnValue();
        if (path == null) return;
        // every node of the route is vetted (not only the end, and also for a single target): a detour that dips over the border on its way to an allowed end is dropped, the goal picks again
        if (!RegionsBackend.mobMayWalk(this.mob, path)) cir.setReturnValue(null);
    }
}
