package net.fissionmc.backend.mixin.world.level.block.piston;

import net.fissionmc.backend.relay.RegionsBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * FissionMC static regions: a piston never moves blocks across a region border. Moving blocks are block entities with their own ticking, which a ghost copy neither ticks nor has the data of;
 * replicating the half-moved structure would leave a moving_piston block without its entity on the other backend. The decision: a push or pull that touches a chunk this backend does not own
 * is refused like a blocked piston (no block moves, nothing is corrupted). New file.
 */
@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverRegionMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private BlockPos pistonPos;
    @Shadow @Final private BlockPos startPos;
    @Shadow @Final private Direction pushDirection;
    @Shadow @Final private List<BlockPos> toPush;
    @Shadow @Final private List<BlockPos> toDestroy;

    @Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
    private void fission$notAcrossBorders(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !(this.level instanceof ServerLevel server)) return;
        boolean ok = RegionsBackend.ownsBlockAt(server, this.pistonPos) && RegionsBackend.ownsBlockAt(server, this.startPos);
        for (BlockPos p : this.toPush) {
            if (!ok) break;
            ok = RegionsBackend.ownsBlockAt(server, p) && RegionsBackend.ownsBlockAt(server, p.relative(this.pushDirection));
        }
        for (BlockPos p : this.toDestroy) {
            if (!ok) break;
            ok = RegionsBackend.ownsBlockAt(server, p);
        }
        if (!ok) {
            RegionsBackend.pistonsBlocked.incrementAndGet();
            cir.setReturnValue(false);
        }
    }
}
