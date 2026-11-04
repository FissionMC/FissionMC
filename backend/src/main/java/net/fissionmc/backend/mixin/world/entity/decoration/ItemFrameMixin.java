package net.fissionmc.backend.mixin.world.entity.decoration;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/decoration/ItemFrame.java.patch */
@Mixin(ItemFrame.class)
public abstract class ItemFrameMixin {
    // TODO(behaviour): createBoundingBox should delegate to createBoundingBoxStatic; cachedMapId, push/spawnAtLocation/setItem overloads.

    // Paper start - add static method
    // Surface: public (see surface/members/entitytypes.txt). Mixin requires non-public statics; the loader widens it.
    private static AABB createBoundingBoxStatic(BlockPos blockPos, Direction direction, boolean hasFramedMap) {
        // Paper end - add static method
        float shiftToBlockWall = 0.46875F;
        Vec3 position = Vec3.atCenterOf(blockPos).relative(direction, -0.46875);
        float width = hasFramedMap ? 1.0F : 0.75F;
        float height = hasFramedMap ? 1.0F : 0.75F;
        Direction.Axis axis = direction.getAxis();
        double xSize = axis == Direction.Axis.X ? 0.0625 : width;
        double ySize = axis == Direction.Axis.Y ? 0.0625 : height;
        double zSize = axis == Direction.Axis.Z ? 0.0625 : width;
        return AABB.ofSize(position, xSize, ySize, zSize);
    }

    @Shadow @Final private static EntityDataAccessor<ItemStack> DATA_ITEM;

    @Shadow private void onItemChanged(final ItemStack item) { throw new AssertionError(); }

    // CraftBukkit start
    // TODO(behaviour): vanilla setItem(ItemStack, boolean) should delegate here with playSound = true (Paper also only plays the sound when updateNeighbours).
    public void setItem(ItemStack itemStack, final boolean updateNeighbours, boolean playSound) {
        // CraftBukkit end
        final ItemFrame self = (ItemFrame) (Object) this;
        if (!itemStack.isEmpty()) {
            itemStack = itemStack.copyWithCount(1);
        }

        this.onItemChanged(itemStack);
        self.getEntityData().set(DATA_ITEM, itemStack);
        if (!itemStack.isEmpty() && updateNeighbours && playSound) { // CraftBukkit // Paper - only play sound when update flag is set
            self.playSound(self.getAddItemSound(), 1.0F, 1.0F);
        }

        if (updateNeighbours && self.getPos() != null) {
            self.level().updateNeighbourForOutputSignal(self.getPos(), Blocks.AIR);
        }
    }
}
