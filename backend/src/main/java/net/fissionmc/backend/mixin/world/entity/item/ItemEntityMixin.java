package net.fissionmc.backend.mixin.world.entity.item;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Members Paper adds to {@link ItemEntity} (paper-server/patches/sources/net/minecraft/world/entity/item/ItemEntity.java.patch).
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    private boolean canMobPickup = true; // Paper - Item#canEntityPickup // TODO(behaviour): checked in Mob#aiStep pickup loop
    // TODO(behaviour): Paper - Alternative item-despawn-rate (despawnRate), despawn/merge hunks in tick()
    private net.kyori.adventure.util.TriState frictionState = net.kyori.adventure.util.TriState.NOT_SET; // Paper - Friction API // TODO(behaviour): tick() ground friction + save/load
}
