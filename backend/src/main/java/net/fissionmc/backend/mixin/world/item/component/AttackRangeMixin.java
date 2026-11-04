package net.fissionmc.backend.mixin.world.item.component;

import net.minecraft.world.item.component.AttackRange;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Members Paper adds to {@link AttackRange} (paper-server/patches/sources/net/minecraft/world/item/component/AttackRange.java.patch).
 */
@Mixin(AttackRange.class)
public abstract class AttackRangeMixin {
    private static final AttackRange CODEC_DEFAULT = new AttackRange(0.0F, 3.0F, 0.0F, 5.0F, 0.3F, 1.0F); // Paper - add back defaults instance
}
