package net.fissionmc.backend.mixin.world.ticks;

import net.fissionmc.backend.blocks.TickLevelBinding;
import net.fissionmc.backend.relay.BorderForwardBackend;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FissionMC static regions: a scheduled block/fluid tick for a position in a chunk another backend owns (a ghost here, never ticked) is sent to the owner instead of being queued on the ghost
 * (where it would be dropped when due). New file; unbound queues (before the level's first tick) and region-less runs are untouched.
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksRegionMixin<T> implements TickLevelBinding {
    @Unique private ServerLevel fission$level;

    @Override
    public void fission$bind(ServerLevel level) {
        this.fission$level = level;
    }

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void fission$forwardGhostTick(ScheduledTick<T> tick, CallbackInfo ci) {
        ServerLevel level = this.fission$level;
        if (level != null && BorderForwardBackend.forwardTick(level, tick)) {
            ci.cancel();
        }
    }
}
