package net.fissionmc.backend.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.event.block.BlockPhysicsEvent;

/** Paper: NeighborUpdater.java.patch - BlockPhysicsEvent for neighbor updates (with source block). */
public final class PaperPhysics {
    private PaperPhysics() {
    }

    /** @return true when the event was cancelled (the neighbor update must be skipped) */
    public static boolean physicsCancelled(final Level level, final BlockState state, final BlockPos pos, final BlockPos sourcePos) {
        final BlockPhysicsEvent event = new BlockPhysicsEvent(CraftBlock.at(level, pos), state.asBlockData(), CraftBlock.at(level, sourcePos));
        level.getCraftServer().getPluginManager().callEvent(event);
        return event.isCancelled();
    }
}
