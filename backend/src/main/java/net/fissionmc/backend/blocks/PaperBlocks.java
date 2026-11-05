package net.fissionmc.backend.blocks;

import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Main-thread state the blocks-area mixins share where Paper changes a signature or a record that Mixin cannot change.
 */
public final class PaperBlocks {
    private PaperBlocks() {
    }

    /**
     * Paper - track more context in interaction result: Paper adds {@code PaperSuccessContext(placedPos)} to
     * {@code InteractionResult.Success}; Mixin cannot add a record component, so BlockItem#place records the position
     * here and ItemStack#useOn consumes it.
     */
    public static @Nullable BlockPos placedPos;

    /**
     * CraftBukkit - onPlace(..., UseOnContext): Paper threads the use-on context into Block#onPlace for ItemStack#useOn's
     * deferred onPlace calls (fire blocks read the player from it). Set around that call, null otherwise.
     */
    public static net.minecraft.world.item.context.@Nullable UseOnContext onPlaceContext;

    /** Paper - fix drops not preventing stats/food exhaustion: Block#playerDestroy(..., includeDrops, dropExp). */
    public static boolean playerDestroyIncludeDrops = true;
    public static boolean playerDestroyDropExp = true;
}
