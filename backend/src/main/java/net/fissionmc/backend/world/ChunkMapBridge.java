package net.fissionmc.backend.world;

/** FissionMC: implemented by ChunkMapMixin; Paper's {@code ChunkMap.callbackExecutor} field. */
public interface ChunkMapBridge {
    ChunkCallbackExecutor fission$callbackExecutor();
}
