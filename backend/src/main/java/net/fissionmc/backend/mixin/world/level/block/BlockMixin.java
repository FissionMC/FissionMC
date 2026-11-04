package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/block/Block.java.patch */
@Mixin(Block.class)
public abstract class BlockMixin {
    // Surface: public static final (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static final int UPDATE_SKIP_POI = 4096; // Paper - temporary flag until ItemStack cleanup is merged
    // TODO(behaviour): Level#setBlock / PoiManager honour UPDATE_SKIP_POI
}
