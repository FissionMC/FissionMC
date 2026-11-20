package net.fissionmc.backend.support;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Shared by the Shearable mixins: Paper's PlayerShearEntityEvent hunk of mobInteract and the custom shear drops plumbing. */
public final class ShearSupport {
    private ShearSupport() {
    }

    /** Fires PlayerShearEntityEvent; returns the (possibly listener edited) drops, or null when the event was cancelled. */
    public static @Nullable List<ItemStack> fire(final Player player, final Entity sheared, final ItemStack tool, final InteractionHand hand, final List<ItemStack> defaultDrops) {
        final org.bukkit.event.player.PlayerShearEntityEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.handlePlayerShearEntityEvent(player, sheared, tool, hand, defaultDrops);
        if (event.isCancelled()) {
            return null;
        }
        return org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getDrops());
    }

    /** Collector for generateDefaultDrops: splits stacks into single items like Paper (sheep, mooshroom). */
    public static BiConsumer<ServerLevel, ItemStack> splitCollector(final List<ItemStack> into) {
        return (ignored, stack) -> {
            for (int i = 0; i < stack.getCount(); ++i) into.add(stack.copyWithCount(1));
        };
    }

    public static BiConsumer<ServerLevel, ItemStack> collector(final List<ItemStack> into) {
        return (ignored, stack) -> into.add(stack);
    }

    public static List<ItemStack> newDrops() {
        return new ArrayList<>();
    }

    /** Replaces the loot table roll of a vanilla shear body with the given drops. */
    public static void deliver(final List<ItemStack> drops, final ServerLevel level, final BiConsumer<ServerLevel, ItemStack> consumer) {
        for (final ItemStack drop : drops) {
            consumer.accept(level, drop);
        }
    }
}
