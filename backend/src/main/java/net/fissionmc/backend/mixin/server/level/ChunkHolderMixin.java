package net.fissionmc.backend.mixin.server.level;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/server/level/ChunkHolder.java.patch */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {
    @Shadow public int oldTicketLevel;

    // CraftBukkit start
    public LevelChunk getFullChunkNow() {
        // Note: We use the oldTicketLevel for isLoaded checks.
        if (!ChunkLevel.fullStatus(this.oldTicketLevel).isOrAfter(FullChunkStatus.FULL)) return null;
        return this.getFullChunkNowUnchecked();
    }

    public LevelChunk getFullChunkNowUnchecked() {
        return (LevelChunk) ((ChunkHolder) (Object) this).getChunkIfPresentUnchecked(ChunkStatus.FULL);
    }
    // CraftBukkit end
    // TODO(behaviour): oldTicketLevel maintenance, callEventIfUnloading, SPIGOT-6086 bounds check
}
