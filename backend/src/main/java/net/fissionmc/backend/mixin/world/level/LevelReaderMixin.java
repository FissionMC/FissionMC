package net.fissionmc.backend.mixin.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/LevelReader.java.patch (getChunkIfLoadedImmediately(int, int) is abstract, see surface) */
@Mixin(LevelReader.class)
public interface LevelReaderMixin {
    default @Nullable ChunkAccess getChunkIfLoadedImmediately(BlockPos pos) { return ((LevelReader) this).getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4); }
}
