package net.fissionmc.backend.mixin.world.level.chunk;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.fissionmc.backend.replication.Replication;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * FissionMC replication: reports block changes that actually happened in a loaded chunk of a ServerLevel (thin shim, logic is Kotlin).
 *
 * <p>Hooked at {@code LevelChunk.setBlockState}, the funnel under {@code Level.setBlock}: that covers the Bukkit API path (CraftBlock.setBlockData -> Level.setBlock) AND
 * plugins that write through the chunk directly (WorldEdit's NMS adapters, see .dev/docs/design/plugin-compat.md). A WrapMethod instead of a RETURN inject because the
 * Paper port's {@code LevelMixin} / {@code LevelChunkMixin} return early in places. Direct {@code LevelChunkSection.setBlockState} writers (FAWE style) are not covered.
 * The method returns the old state, or null when nothing changed.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkReplicationMixin {
    @WrapMethod(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;")
    private BlockState fission$replicateSetBlock(BlockPos pos, BlockState state, int flags, Operation<BlockState> original) {
        final BlockState old = original.call(pos, state, flags);
        if (old != null && ((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level) {
            Replication.onSetBlock(level, pos, state);
        }
        return old;
    }
}
