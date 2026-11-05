package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/level/block/BaseFireBlock.java.patch
 * Done: fireExtinguished. TODO(behaviour): EntityInsideBlockEvent + EntityCombustByBlockEvent in fireIgnite (needs pos through InsideBlockEffect),
 * onPlace(context) portal creation player, inPortalDimension getTypeKey.
 */
@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockMixin {
    public void fireExtinguished(LevelAccessor world, BlockPos position) {
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.callBlockFadeEvent(world, position, Blocks.AIR.defaultBlockState()).isCancelled()) {
            world.removeBlock(position, false);
        }
    }
}
