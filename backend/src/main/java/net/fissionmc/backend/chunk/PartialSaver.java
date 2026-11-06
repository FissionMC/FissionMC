package net.fissionmc.backend.chunk;

/** Implemented by the ChunkMap mixin: writes the chunks that are still being generated (see ChunkMapPartialSaveMixin). */
public interface PartialSaver {
    int fission$savePartial();
}
