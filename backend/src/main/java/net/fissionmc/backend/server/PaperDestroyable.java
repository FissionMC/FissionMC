package net.fissionmc.backend.server;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * FissionMC: stand-in for Paper's Block#isDestroyable / BlockStateBase#isDestroyable (owned by the blocks area,
 * patch sources/net/minecraft/world/level/block/Block.java.patch). TODO(merge): replace with state.isDestroyable() once blocks is merged.
 */
public final class PaperDestroyable {
    private PaperDestroyable() {
    }

    public static boolean isDestroyable(final BlockState state) {
        return io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.allowPermanentBlockBreakExploits ||
            state.getBlock() != Blocks.BARRIER &&
            state.getBlock() != Blocks.BEDROCK &&
            state.getBlock() != Blocks.END_PORTAL_FRAME &&
            state.getBlock() != Blocks.END_PORTAL &&
            state.getBlock() != Blocks.END_GATEWAY &&
            state.getBlock() != Blocks.COMMAND_BLOCK &&
            state.getBlock() != Blocks.REPEATING_COMMAND_BLOCK &&
            state.getBlock() != Blocks.CHAIN_COMMAND_BLOCK &&
            state.getBlock() != Blocks.STRUCTURE_BLOCK &&
            state.getBlock() != Blocks.JIGSAW;
    }
}
