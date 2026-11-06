package net.fissionmc.backend.mixin.server;

import java.nio.file.Path;
import net.fissionmc.backend.relay.TravelBackend;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FissionMC: the advancements file a backend just saved goes to the master. New file next to the ported PlayerAdvancementsMixin. */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsRelayMixin {
    @Shadow @Final private Path playerSavePath;

    @Inject(method = "save", at = @At("RETURN"))
    private void fission$reportAdvancements(CallbackInfo ci) {
        TravelBackend.onFileSaved(this.playerSavePath, 1);
    }
}
