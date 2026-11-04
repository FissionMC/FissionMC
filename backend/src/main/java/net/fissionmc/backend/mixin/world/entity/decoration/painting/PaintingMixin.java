package net.fissionmc.backend.mixin.world.entity.decoration.painting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Paper: sources/net/minecraft/world/entity/decoration/painting/Painting.java.patch */
@Mixin(Painting.class)
public abstract class PaintingMixin {
    // TODO(behaviour): calculateBoundingBox should delegate to calculateBoundingBoxStatic.

    // CraftBukkit start
    // Surface: public (see surface/members/entitytypes.txt). Mixin requires non-public statics; the loader widens it.
    private static AABB calculateBoundingBoxStatic(final BlockPos pos, final Direction direction, final int width, final int height) {
        // CraftBukkit end
        float shiftToBlockWall = 0.46875F;
        Vec3 attachedToWall = Vec3.atCenterOf(pos).relative(direction, -0.46875);
        // CraftBukkit start
        double horizontalOffset = PaintingMixin.fission$offsetForPaintingSize(width);
        double verticalOffset = PaintingMixin.fission$offsetForPaintingSize(height);
        // CraftBukkit end
        Direction left = direction.getCounterClockWise();
        Vec3 position = attachedToWall.relative(left, horizontalOffset).relative(Direction.UP, verticalOffset);
        Direction.Axis axis = direction.getAxis();
        // CraftBukkit start
        double xSize = axis == Direction.Axis.X ? 0.0625 : width;
        double ySize = height;
        double zSize = axis == Direction.Axis.Z ? 0.0625 : width;
        // CraftBukkit end
        return AABB.ofSize(position, xSize, ySize, zSize);
    }

    // FissionMC: Paper makes offsetForPaintingSize static; the vanilla instance method keeps its name, so the static copy is renamed.
    @Unique
    private static double fission$offsetForPaintingSize(final int size) { // CraftBukkit - static
        return size % 2 == 0 ? 0.5 : 0.0;
    }
}
