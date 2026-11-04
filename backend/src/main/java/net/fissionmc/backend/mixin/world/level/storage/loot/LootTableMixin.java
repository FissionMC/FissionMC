package net.fissionmc.backend.mixin.world.level.storage.loot;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/storage/loot/LootTable.java.patch */
@Mixin(LootTable.class)
public abstract class LootTableMixin {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private Optional<Identifier> randomSequence;
    @Shadow private ObjectArrayList<ItemStack> getRandomItems(final LootContext context) { throw new AssertionError(); }
    @Shadow private void shuffleAndSplitItems(final ObjectArrayList<ItemStack> result, final int availableSlots, final RandomSource random) {}
    @Shadow private List<Integer> getAvailableSlots(final Container container, final RandomSource random) { throw new AssertionError(); }

    private org.bukkit.craftbukkit.CraftLootTable craftLootTable; // CraftBukkit

    // TODO(behaviour): vanilla fill(Container, LootParams, long) -> Paper routes through this method (plugin = false)
    public void fill(Container container, LootParams params, RandomSource randomSource, boolean plugin) {
        // CraftBukkit end
        LootContext context = new LootContext.Builder(params).withOptionalRandomSource(randomSource).create(this.randomSequence);
        ObjectArrayList<ItemStack> itemStacks = this.getRandomItems(context);
        RandomSource random = context.getRandom();
        // CraftBukkit start
        org.bukkit.event.world.LootGenerateEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callLootGenerateEvent(container, (LootTable) (Object) this, context, itemStacks, plugin);
        if (event.isCancelled()) {
            return;
        }
        itemStacks = event.getLoot().stream().map(org.bukkit.craftbukkit.inventory.CraftItemStack::asNMSCopy).collect(ObjectArrayList.toList());
        // CraftBukkit end
        List<Integer> availableSlots = this.getAvailableSlots(container, random);
        this.shuffleAndSplitItems(itemStacks, availableSlots.size(), random);

        for (ItemStack itemStack : itemStacks) {
            if (availableSlots.isEmpty()) {
                LOGGER.warn("Tried to over-fill a container");
                return;
            }

            if (itemStack.isEmpty()) {
                container.setItem(availableSlots.remove(availableSlots.size() - 1), ItemStack.EMPTY);
            } else {
                container.setItem(availableSlots.remove(availableSlots.size() - 1), itemStack);
            }
        }
    }
}
