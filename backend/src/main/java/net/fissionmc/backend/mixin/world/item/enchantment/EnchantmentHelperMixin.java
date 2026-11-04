package net.fissionmc.backend.mixin.world.item.enchantment;

import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/item/enchantment/EnchantmentHelper.java.patch */
@Mixin(EnchantmentHelper.class)
public abstract class EnchantmentHelperMixin {
    @Shadow
    private static DataComponentType<ItemEnchantments> getComponentType(final ItemStack itemStack) {
        throw new AssertionError();
    }

    // Paper start - allowing updating enchantments on items without component
    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static ItemEnchantments updateEnchantments(final ItemStack itemStack, final Consumer<ItemEnchantments.Mutable> consumer, final boolean createComponentIfMissing) {
        // Paper end - allowing updating enchantments on items without component
        DataComponentType<ItemEnchantments> componentType = getComponentType(itemStack);
        ItemEnchantments oldEnchantments = createComponentIfMissing ? itemStack.getOrDefault(componentType, ItemEnchantments.EMPTY) : itemStack.get(componentType); // Paper - allowing updating enchantments on items without component
        if (oldEnchantments == null) {
            return ItemEnchantments.EMPTY;
        }

        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(oldEnchantments);
        consumer.accept(mutable);
        ItemEnchantments newEnchantments = mutable.toImmutable();
        itemStack.set(componentType, newEnchantments);
        return newEnchantments;
    }
}
