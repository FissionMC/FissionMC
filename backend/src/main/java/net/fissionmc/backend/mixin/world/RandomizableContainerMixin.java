package net.fissionmc.backend.mixin.world;

import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/RandomizableContainer.java.patch
 * TODO(behaviour): tryLoadLootTable/trySaveLootTable/unpackLootTable consult lootableData() (LootTable API).
 */
@Mixin(RandomizableContainer.class)
public interface RandomizableContainerMixin {
    // Paper start - LootTable API
    default void unpackLootTable(@Nullable final Player player, final boolean forceClearLootTable) {
        // TODO(behaviour): Paper moves the vanilla body here and honours lootableData() replenish rules / forceClearLootTable
        ((RandomizableContainer) this).unpackLootTable(player);
    }

    default com.destroystokyo.paper.loottable.@Nullable PaperLootableInventoryData lootableData() {
        return null; // some containers don't really have a "replenish" ability like decorated pots
    }

    default com.destroystokyo.paper.loottable.PaperLootableInventory getLootableInventory() {
        final RandomizableContainer self = (RandomizableContainer) this;
        org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(java.util.Objects.requireNonNull(self.getLevel(), "Cannot manage loot tables on block entities not in world"), self.getBlockPos());
        return (com.destroystokyo.paper.loottable.PaperLootableInventory) block.getState(false);
    }
    // Paper end - LootTable API
}
