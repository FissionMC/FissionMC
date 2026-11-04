package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/block/SpawnerBlock.java.patch */
@Mixin(SpawnerBlock.class)
public abstract class SpawnerBlockMixin {

    // CraftBukkit start - Delegate to getExpDrop
    // TODO(behaviour): Paper removes the XP drop from spawnAfterBreak (see BlockMixin#getExpDrop).
    @SuppressWarnings("unused")
    public int getExpDrop(final BlockState state, final ServerLevel level, final BlockPos pos, final ItemStack tool, final boolean dropExperience) {
        if (dropExperience) {
            RandomSource random = level.getRandom();
            int magicCount = 15 + random.nextInt(15) + random.nextInt(15);
            // this.popExperience(level, pos, magicCount);
            return magicCount;
        }
        return 0;
        // CraftBukkit end
    }
}
