package net.fissionmc.backend.mixin.world.clock;

import java.util.function.Consumer;
import net.fissionmc.backend.relay.WorldSettingsBackend;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FissionMC world settings sync: every change of a world clock (/time set|add, time markers, rate, paused, sleeping, plugins) goes through modifyClock; the master owns the clocks. New file. */
@Mixin(ServerClockManager.class)
public abstract class ServerClockManagerSyncMixin {
    @Inject(method = "modifyClock", at = @At("TAIL"))
    private void fission$clockChanged(Holder<WorldClock> clock, Consumer<?> action, CallbackInfo ci) {
        WorldSettingsBackend.onClockChanged(clock);
    }
}
