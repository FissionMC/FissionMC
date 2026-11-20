package net.fissionmc.backend.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.jspecify.annotations.Nullable;

/**
 * Paper: CauldronInteractions / LayeredCauldronBlock patches (CauldronLevelChangeEvent). Paper adds a hitDirection parameter to
 * CauldronInteraction#interact and entity/reason parameters to LayeredCauldronBlock#lowerFillLevel; Mixin cannot change those
 * signatures, so the context travels through these main-thread fields.
 */
public final class PaperCauldrons {
    private PaperCauldrons() {
    }

    /** Hit direction of the current CauldronInteraction#interact call (null for non-player callers). */
    public static @Nullable Direction hitDirection;
    /** Entity that triggers the current 3-arg {@code lowerFillLevel} (burning entity), null otherwise. */
    public static @Nullable Entity entity;

    /** Paper - Call CauldronLevelChangeEvent: LayeredCauldronBlock#lowerFillLevel(state, level, pos, entity, reason). */
    public static boolean lowerFillLevel(final BlockState state, final Level level, final BlockPos pos, final @Nullable Entity entity, final CauldronLevelChangeEvent.ChangeReason reason) {
        final int newLevel = state.getValue(LayeredCauldronBlock.LEVEL) - 1;
        final BlockState newState = newLevel == 0 ? Blocks.CAULDRON.defaultBlockState() : state.setValue(LayeredCauldronBlock.LEVEL, newLevel);
        final boolean success = CraftEventFactory.handleCauldronLevelChangeEvent(level, pos, newState, entity, reason);
        if (success) {
            level.gameEvent(net.minecraft.world.level.gameevent.GameEvent.BLOCK_CHANGE, pos, net.minecraft.world.level.gameevent.GameEvent.Context.of(newState));
        }
        return success;
    }
}
