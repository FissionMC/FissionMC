package net.fissionmc.backend.blocks;

import net.minecraft.server.level.ServerLevel;

/** Implemented by the LevelTicks mixin: tells a tick queue which level it belongs to (LevelTicks itself does not know). */
public interface TickLevelBinding {
    void fission$bind(ServerLevel level);
}
