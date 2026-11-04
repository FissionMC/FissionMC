package net.fissionmc.craft.compat;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * FissionMC: Paper's {@code net.minecraft.world.entity.Entity.DefaultDrop} (nested record added by
 * Paper's Entity.java.patch), moved here because Mixin cannot add nested classes to vanilla classes.
 */
// Paper start - Restore vanilla drops behavior
public record DefaultDrop(Item item, org.bukkit.inventory.ItemStack stack, java.util.function.@Nullable Consumer<ItemStack> dropConsumer) {
    public DefaultDrop(final ItemStack stack, final java.util.function.Consumer<ItemStack> dropConsumer) {
        this(stack.getItem(), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(stack), dropConsumer);
    }

    public void runConsumer(final java.util.function.Consumer<org.bukkit.inventory.ItemStack> fallback) {
        if (this.dropConsumer == null || org.bukkit.craftbukkit.inventory.CraftItemType.bukkitToMinecraft(this.stack.getType()) != this.item) {
            fallback.accept(this.stack);
        } else {
            this.dropConsumer.accept(org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(this.stack));
        }
    }
}
// Paper end - Restore vanilla drops behavior
