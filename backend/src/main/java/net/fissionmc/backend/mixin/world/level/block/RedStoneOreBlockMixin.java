package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/block/RedStoneOreBlock.java.patch */
@Mixin(RedStoneOreBlock.class)
public abstract class RedStoneOreBlockMixin {

    // CraftBukkit start - Delegate to getExpDrop
    // TODO(behaviour): Paper removes the XP drop from spawnAfterBreak (see BlockMixin#getExpDrop).
    @SuppressWarnings("unused")
    public int getExpDrop(final BlockState state, final ServerLevel level, final BlockPos pos, final ItemStack tool, final boolean dropExperience) {
        if (dropExperience) {
            // FissionMC: Paper's tryDropExperience returns the amount instead of popping it
            final int experience = EnchantmentHelper.processBlockExperience(level, tool, UniformInt.of(1, 5).sample(level.getRandom()));
            return experience > 0 ? experience : 0;
        }
        return 0;
        // CraftBukkit end
    }
}
