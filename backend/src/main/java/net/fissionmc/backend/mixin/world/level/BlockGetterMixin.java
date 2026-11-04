package net.fissionmc.backend.mixin.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/level/BlockGetter.java.patch.
 * getBlockStateIfLoaded/getFluidIfLoaded are abstract (surface); implementors: Level, WorldGenRegion, EmptyBlockGetter,
 * PathNavigationRegion, LevelChunk, ProtoChunk (this area) and FlowingFluid's anonymous getter (blocks area).
 */
@Mixin(BlockGetter.class)
public interface BlockGetterMixin {
    // FissionMC: Paper declares these abstract. They need a declaration in BlockGetter itself or invokeinterface
    // BlockGetter.getBlockStateIfLoaded fails to resolve at runtime; implementors override these (see class comment).
    default @Nullable BlockState getBlockStateIfLoaded(BlockPos pos) {
        throw new AbstractMethodError("FissionMC: " + this.getClass().getName() + " does not implement getBlockStateIfLoaded");
    }

    default @Nullable FluidState getFluidIfLoaded(BlockPos pos) {
        throw new AbstractMethodError("FissionMC: " + this.getClass().getName() + " does not implement getFluidIfLoaded");
    }

    // Paper start - if loaded util
    default net.minecraft.world.level.block.@Nullable Block getBlockIfLoaded(BlockPos pos) {
        BlockState type = ((BlockGetter) this).getBlockStateIfLoaded(pos);
        return type == null ? null : type.getBlock();
    }
    // Paper end

    // TODO(behaviour): vanilla clip(ClipContext) -> Paper delegates to clip(c, (Predicate) null)
    default BlockHitResult clip(ClipContext c, BlockPos pos) {
        return this.clip(c, pos, null);
    }

    default BlockHitResult clip(ClipContext c, BlockPos pos, java.util.function.@Nullable Predicate<? super org.bukkit.block.Block> canCollide) {
        final BlockGetter self = (BlockGetter) this;
        // Prevent raytrace from loading chunks
        ClipContext context = c;
        BlockState blockState = self.getBlockStateIfLoaded(pos);
        if (blockState == null) {
            // copied the last function parameter (listed below)
            Vec3 delta = context.getFrom().subtract(context.getTo());
            return BlockHitResult.miss(context.getTo(), Direction.getApproximateNearest(delta.x, delta.y, delta.z), BlockPos.containing(c.getTo()));
        }
        // Paper end - Prevent raytrace from loading chunks
        if (blockState.isAir() || (canCollide != null && this instanceof LevelAccessor levelAccessor && !canCollide.test(org.bukkit.craftbukkit.block.CraftBlock.at(levelAccessor, pos)))) return null; // Paper - Perf: optimise air cases & check canCollide predicate
        FluidState fluidState = blockState.getFluidState(); // Paper - Perf: don't need to go to world state again
        Vec3 from = context.getFrom();
        Vec3 to = context.getTo();
        VoxelShape blockShape = context.getBlockShape(blockState, self, pos);
        BlockHitResult blockResult = self.clipWithInteractionOverride(from, to, pos, blockShape, blockState);
        VoxelShape fluidShape = context.getFluidShape(fluidState, self, pos);
        BlockHitResult liquidResult = fluidShape.clip(from, to, pos);
        double blockDistanceSquared = blockResult == null ? Double.MAX_VALUE : context.getFrom().distanceToSqr(blockResult.getLocation());
        double liquidDistanceSquared = liquidResult == null ? Double.MAX_VALUE : context.getFrom().distanceToSqr(liquidResult.getLocation());
        return blockDistanceSquared <= liquidDistanceSquared ? blockResult : liquidResult;
    }

    default BlockHitResult clip(ClipContext c, java.util.function.@Nullable Predicate<? super org.bukkit.block.Block> canCollide) {
        // Paper end - Add predicate for blocks when raytracing
        return BlockGetter.traverseBlocks(c.getFrom(), c.getTo(), c, (context, pos) -> {
            return this.clip(context, pos, canCollide); // CraftBukkit - moved into separate method // Paper - Add predicate for blocks when raytracing
        }, context -> {
            Vec3 delta = context.getFrom().subtract(context.getTo());
            return BlockHitResult.miss(context.getTo(), Direction.getApproximateNearest(delta.x, delta.y, delta.z), BlockPos.containing(context.getTo()));
        });
    }
}
