package net.fissionmc.backend.world;

import net.minecraft.server.level.ChunkMap;

/** FissionMC: implemented by ChunkHolderMixin; Paper's protected {@code ChunkHolder.callEventIfUnloading(ChunkMap)}. */
public interface ChunkHolderBridge {
    void fission$callEventIfUnloading(ChunkMap chunkMap);
}
