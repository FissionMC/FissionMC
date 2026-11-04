package net.fissionmc.backend.mixin.world.item;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/item/BoneMealItem.java.patch */
@Mixin(BoneMealItem.class)
public abstract class BoneMealItemMixin {
    // CraftBukkit start - extract bonemeal application logic to separate, static method
    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    // The vanilla useOn body does not depend on the item instance, so delegate to it.
    // TODO(behaviour): Paper moves the useOn body here and guards causeUseVibration against a null player (SPIGOT-7518).
    private static InteractionResult applyBonemeal(final UseOnContext context) {
        return Items.BONE_MEAL.useOn(context);
    }
    // CraftBukkit end
}
