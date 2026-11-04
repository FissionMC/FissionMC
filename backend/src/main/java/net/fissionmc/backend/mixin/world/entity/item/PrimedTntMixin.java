package net.fissionmc.backend.mixin.world.entity.item;

import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Members Paper adds to {@link PrimedTnt} (paper-server/patches/sources/net/minecraft/world/entity/item/PrimedTnt.java.patch).
 */
@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin {
    private boolean isIncendiary = false; // CraftBukkit // TODO(behaviour): explode() uses it (ExplosionPrimeEvent)
}
