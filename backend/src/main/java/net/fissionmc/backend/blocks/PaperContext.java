package net.fissionmc.backend.blocks;

import org.jspecify.annotations.Nullable;

/**
 * Per-thread context for Paper hunks that widen a vanilla method's parameter list (a Mixin cannot change a signature):
 * the caller stores the extra arguments here, the callee reads them while the vanilla call runs.
 */
public final class PaperContext {
    private PaperContext() {
    }

    /** FurnaceResultSlot -> AbstractFurnaceBlockEntity.awardUsedRecipesAndPopExperience(player, stack, amount). */
    public record FurnaceExtract(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.item.ItemStack stack, int amount) {
    }

    public static final ThreadLocal<@Nullable FurnaceExtract> FURNACE_EXTRACT = new ThreadLocal<>();
    /** position of the furnace whose getRecipesToAwardAndPopExperience is running (createExperience is static). */
    public static final ThreadLocal<net.minecraft.core.@Nullable BlockPos> FURNACE_POS = new ThreadLocal<>();

    /** ComposterBlock.addLayer(..., addedStack): the stack being composted, and whether the compost / change-block event was cancelled (Paper returns null). */
    public static final ThreadLocal<net.minecraft.world.item.@Nullable ItemStack> COMPOST_STACK = new ThreadLocal<>();
    public static final ThreadLocal<@Nullable Boolean> COMPOST_CANCELLED = new ThreadLocal<>();

    public static boolean takeCompostCancelled() {
        final boolean cancelled = COMPOST_CANCELLED.get() != null;
        COMPOST_CANCELLED.remove();
        return cancelled;
    }
}
