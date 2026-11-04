package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/block/Block.java.patch */
@Mixin(Block.class)
public abstract class BlockMixin {
    // Surface: public static final (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static final int UPDATE_SKIP_POI = 4096; // Paper - temporary flag until ItemStack cleanup is merged
    // TODO(behaviour): Level#setBlock / PoiManager honour UPDATE_SKIP_POI

    // Paper start - Properly handle xp dropping
    // Surface: public static (see surface/members/blocks.txt); the loader widens it.
    private static void dropResources(
        final BlockState state,
        final Level level,
        final BlockPos pos,
        final @Nullable BlockEntity blockEntity,
        final @Nullable Entity breaker,
        final ItemStack tool
        , final boolean dropExperience // Paper - Properly handle xp dropping
    ) {
        if (level instanceof ServerLevel serverLevel) {
            Block.getDrops(state, serverLevel, pos, blockEntity, breaker, tool).forEach(stack -> Block.popResource(level, pos, stack));
            state.spawnAfterBreak(serverLevel, pos, tool, dropExperience); // Paper - Properly handle xp dropping
        }
    }
    // Paper end - Properly handle xp dropping

    // CraftBukkit start
    // TODO(behaviour): Paper moves XP dropping out of the subclasses' spawnAfterBreak into BlockStateBase#spawnAfterBreak
    // (popExperience(getExpDrop(...))) and makes tryDropExperience return the amount; vanilla subclasses still drop XP
    // themselves in spawnAfterBreak, so the getExpDrop overrides compute the amount without dropping it.
    public int getExpDrop(final BlockState state, final ServerLevel level, final BlockPos pos, final ItemStack tool, final boolean dropExperience) {
        return 0;
    }
    // CraftBukkit end
}
