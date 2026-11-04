package net.fissionmc.backend.mixin.world.item.component;

import net.minecraft.world.item.component.AttackRange;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/item/component/AttackRange.java.patch */
@Mixin(AttackRange.class)
public abstract class AttackRangeMixin {
    // Surface: public static final (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static final AttackRange CODEC_DEFAULT = new AttackRange(0.0F, 3.0F, 0.0F, 5.0F, 0.3F, 1.0F); // Paper - add back defaults instance
}
