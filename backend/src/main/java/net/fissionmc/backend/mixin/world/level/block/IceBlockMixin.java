package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/level/block/IceBlock.java.patch
 * TODO(behaviour): playerDestroy should delegate to afterDestroy (and take includeDrops/dropExp); melt fires BlockFadeEvent.
 */
@Mixin(IceBlock.class)
public abstract class IceBlockMixin {
    // Paper start - Improve Block#breakNaturally API
    public void afterDestroy(Level level, BlockPos pos, ItemStack destroyedWith) {
        // Paper end - Improve Block#breakNaturally API
        if (!EnchantmentHelper.hasTag(destroyedWith, EnchantmentTags.PREVENTS_ICE_MELTING)) {
            if (level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos)) {
                level.removeBlock(pos, false);
                return;
            }

            BlockState below = level.getBlockState(pos.below());
            if (below.is(BlockTags.ICE_MELTS_WHEN_DESTROYED_ABOVE) || below.liquid()) {
                level.setBlockAndUpdate(pos, IceBlock.meltsInto());
            }
        }
    }
}
