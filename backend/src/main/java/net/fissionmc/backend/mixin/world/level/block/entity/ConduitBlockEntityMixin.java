package net.fissionmc.backend.mixin.world.level.block.entity;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/ConduitBlockEntity.java.patch
 * TODO(behaviour): applyEffects split into getRange + applyEffects(level, pos, range), EntityPotionEffectEvent cause, ConduitDamage.
 */
@Mixin(ConduitBlockEntity.class)
public abstract class ConduitBlockEntityMixin {
    // CraftBukkit start
    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static int getRange(List<BlockPos> effectBlocks) {
        // CraftBukkit end
        int activeSize = effectBlocks.size();
        int effectRange = activeSize / 7 * 16;
        // CraftBukkit start
        return effectRange;
    }
    // CraftBukkit end
}
