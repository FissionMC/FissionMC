package net.fissionmc.backend.mixin.world.entity.item;

import net.minecraft.world.entity.item.FallingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Members Paper adds to {@link FallingBlockEntity} (paper-server/patches/sources/net/minecraft/world/entity/item/FallingBlockEntity.java.patch).
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
    private boolean autoExpire = true; // Paper - Expand FallingBlock API // TODO(behaviour): tick() despawn check + save/load
}
