package net.fissionmc.backend.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Main-thread scratch state for the growth/spread/fade event mixins (where Paper changes a local or adds a variable). */
public final class PaperGrowth {
    private PaperGrowth() {
    }

    /** MushroomBlock#randomTick: Paper - Use correct source for mushroom block spread event (the vanilla loop reassigns pos). */
    public static @Nullable BlockPos mushroomSource;

    /** BambooStalkBlock#growBamboo: the two "below" leaf updates Paper moves behind the BlockSpreadEvent. */
    public static @Nullable BlockPos bambooBelowPos;
    public static @Nullable BlockState bambooBelowState;
    public static @Nullable BlockPos bambooTwoBelowPos;
    public static @Nullable BlockState bambooTwoBelowState;

    /** CrafterBlock#dispenseFrom: a cancelled CrafterCraftEvent must skip the "fail" level event that vanilla plays for empty results. */
    public static boolean crafterCancelled;

    /** RedStoneOreBlock#interact: Paper adds an entity parameter (EntityChangeBlockEvent); set by the attack/stepOn/useItemOn wrappers. */
    public static net.minecraft.world.entity.@Nullable Entity interactor;
}
