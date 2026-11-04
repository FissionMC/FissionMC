package net.fissionmc.backend.mixin.server;

import net.minecraft.server.ServerTickRateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/server/ServerTickRateManager.java.patch */
@Mixin(ServerTickRateManager.class)
public abstract class ServerTickRateManagerMixin {
    @Shadow public abstract boolean requestGameToSprint(int time);
    @Shadow public abstract boolean isSprinting();

    private boolean silent; // Paper - silence feedback when API requests sprint

    public boolean requestGameToSprint(final int time, final boolean silent) {
        if (!isSprinting()) this.silent = silent;
        // Paper end - silence feedback when API requests sprint
        // TODO(behaviour): finishTickSprint hunk (skip feedback while silent, then reset silent)
        return this.requestGameToSprint(time); // FissionMC: Paper inlines the vanilla body here
    }
}
