package net.fissionmc.backend.mixin.server.level;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import net.fissionmc.backend.chunk.PartialSaver;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * FissionMC chunk authority: vanilla's periodic save ({@code saveChunkIfNeeded}) only writes FULL chunks; a chunk that is still being generated (the ring
 * of neighbours a forced or player chunk needs) is written when it unloads, which can take as long as the player stays. Another backend that needs such a
 * neighbour would wait for it. This lets the authority code write partial chunks too (vanilla's own {@code save}, so the IO hook pushes them). New file.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapPartialSaveMixin implements PartialSaver {
    @Shadow private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;

    @Shadow
    private boolean save(ChunkAccess chunk) {
        throw new AssertionError();
    }

    @Override
    public int fission$savePartial() {
        int n = 0;
        for (ChunkHolder holder : new ArrayList<>(this.visibleChunkMap.values())) {
            if (!holder.isReadyForSaving()) {
                continue;
            }
            ChunkAccess chunk = holder.getLatestChunk();
            if (chunk == null || chunk instanceof ImposterProtoChunk || chunk instanceof LevelChunk || !chunk.isUnsaved()) {
                continue;
            }
            if (this.save(chunk)) {
                n++;
            }
        }
        return n;
    }
}
