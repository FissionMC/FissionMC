package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SpongeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/level/block/SpongeBlock.java.patch (replaced body: removeWaterBreadthFirstSearch with BlockStateListPopulator + SpongeAbsorbEvent) */
@Mixin(SpongeBlock.class)
public abstract class SpongeBlockMixin {
    @Inject(method = "removeWaterBreadthFirstSearch", at = @At("HEAD"), cancellable = true)
    private void fission$removeWater(final Level level, final BlockPos startPos, final CallbackInfoReturnable<Boolean> cir) {
        org.bukkit.craftbukkit.util.BlockStateListPopulator blockList = new org.bukkit.craftbukkit.util.BlockStateListPopulator(level); // CraftBukkit - Use BlockStateListPopulator
        BlockPos.breadthFirstTraversal(startPos, 6, 65, (pos, consumer) -> {
            for (Direction direction : Direction.values()) {
                consumer.accept(pos.relative(direction));
            }
        }, pos -> {
            if (pos.equals(startPos)) {
                return BlockPos.TraversalNodeStatus.ACCEPT;
            } else {
                BlockState state = blockList.getBlockState(pos);
                FluidState fluidState = blockList.getFluidState(pos);
                if (!fluidState.is(FluidTags.WATER)) {
                    return BlockPos.TraversalNodeStatus.SKIP;
                } else if (state.getBlock() instanceof BucketPickup bucketPickup && !bucketPickup.pickupBlock(null, blockList, pos, state).isEmpty()) { // CraftBukkit
                    return BlockPos.TraversalNodeStatus.ACCEPT;
                } else {
                    if (state.getBlock() instanceof LiquidBlock) {
                        blockList.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()); // CraftBukkit
                    } else {
                        if (!state.is(Blocks.KELP) && !state.is(Blocks.KELP_PLANT) && !state.is(Blocks.SEAGRASS) && !state.is(Blocks.TALL_SEAGRASS)) {
                            return BlockPos.TraversalNodeStatus.SKIP;
                        }

                        blockList.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }

                    return BlockPos.TraversalNodeStatus.ACCEPT;
                }
            }
        });
        java.util.List<org.bukkit.craftbukkit.block.CraftBlockState> snapshots = blockList.getSnapshotBlocks(); // Is a clone
        if (!snapshots.isEmpty()) {
            final org.bukkit.block.Block sponge = org.bukkit.craftbukkit.block.CraftBlock.at(level, startPos);

            org.bukkit.event.block.SpongeAbsorbEvent event = new org.bukkit.event.block.SpongeAbsorbEvent(sponge, (java.util.List<org.bukkit.block.BlockState>) (java.util.List) snapshots);
            if (!event.callEvent()) {
                cir.setReturnValue(false);
                return;
            }

            for (org.bukkit.craftbukkit.block.CraftBlockState snapshot : snapshots) {
                BlockPos blockPos = snapshot.getPosition();
                BlockState state = level.getBlockState(blockPos);
                FluidState fluidState = level.getFluidState(blockPos);

                if (!fluidState.is(FluidTags.WATER)) {
                } else if (state.getBlock() instanceof BucketPickup bucketPickup && !bucketPickup.pickupBlock(null, level, blockPos, state).isEmpty()) {
                } else if (state.getBlock() instanceof LiquidBlock) {
                } else if (state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT) || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS)) {
                    BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(blockPos) : null;

                    if (snapshot.getHandle().isAir()) {
                        Block.dropResources(state, level, blockPos, blockEntity);
                    }
                }
                snapshot.place(snapshot.getFlags());
            }

            cir.setReturnValue(true);
            return;
        }
        cir.setReturnValue(false);
    }
}
