package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.world.level.block.SaplingBlock;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/level/block/SaplingBlock.java.patch
 * TODO(behaviour): advanceTree captures block states and fires StructureGrowEvent using treeType.
 */
@Mixin(SaplingBlock.class)
public abstract class SaplingBlockMixin {
    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static org.bukkit.@org.jspecify.annotations.Nullable TreeType treeType; // CraftBukkit
}
