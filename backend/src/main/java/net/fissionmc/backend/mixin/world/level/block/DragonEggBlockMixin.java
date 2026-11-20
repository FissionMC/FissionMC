package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.util.BlockUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.bukkit.event.block.BlockFromToEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: DragonEggBlock.java.patch - BlockFromToEvent when the egg teleports (body replicated at HEAD, event before the move). */
@Mixin(DragonEggBlock.class)
public abstract class DragonEggBlockMixin {
    @Inject(method = "teleport", at = @At("HEAD"), cancellable = true)
    private void paper$teleport(final BlockState state, final Level level, final BlockPos pos, final CallbackInfo ci) {
        ci.cancel();
        if (level.isClientSide()) {
            return;
        }
        final WorldBorder worldBorder = level.getWorldBorder();
        final RandomSource random = level.getRandom();
        for (int i = 0; i < 1000; ++i) {
            BlockPos testPos = pos.offset(random.nextInt(16) - random.nextInt(16), random.nextInt(8) - random.nextInt(8), random.nextInt(16) - random.nextInt(16));
            if (!level.getBlockState(testPos).isAir() || level.getBlockState(testPos.below()).isAir() || !worldBorder.isWithinBounds(testPos) || !level.isInsideBuildHeight(testPos)) {
                continue;
            }
            // CraftBukkit start
            final org.bukkit.block.Block from = org.bukkit.craftbukkit.block.CraftBlock.at(level, pos);
            final org.bukkit.block.Block to = org.bukkit.craftbukkit.block.CraftBlock.at(level, testPos);
            final BlockFromToEvent event = new BlockFromToEvent(from, to);
            if (!event.callEvent()) {
                return;
            }
            testPos = new BlockPos(event.getToBlock().getX(), event.getToBlock().getY(), event.getToBlock().getZ());
            // CraftBukkit end
            final int packedDiff = BlockUtil.packDifferenceInPosition(pos, testPos, 16, 8, 16);
            level.levelEvent(2015, pos, packedDiff);
            level.setBlock(testPos, state, Block.UPDATE_CLIENTS);
            level.removeBlock(pos, false);
            return;
        }
    }
}
