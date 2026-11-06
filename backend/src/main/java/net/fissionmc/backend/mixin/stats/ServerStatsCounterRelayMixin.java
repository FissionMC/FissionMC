package net.fissionmc.backend.mixin.stats;

import java.nio.file.Path;
import net.fissionmc.backend.relay.TravelBackend;
import net.minecraft.stats.ServerStatsCounter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FissionMC: the stats file a backend just saved goes to the master (master-owned player data). New file. */
@Mixin(ServerStatsCounter.class)
public abstract class ServerStatsCounterRelayMixin {
    @Shadow @Final private Path file;

    @Inject(method = "save", at = @At("RETURN"))
    private void fission$reportStats(CallbackInfo ci) {
        TravelBackend.onFileSaved(this.file, 0);
    }
}
