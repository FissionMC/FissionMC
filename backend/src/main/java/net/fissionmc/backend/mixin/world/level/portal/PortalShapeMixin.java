package net.fissionmc.backend.mixin.world.level.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/level/portal/PortalShape.java.patch (createPortalBlocks: PortalCreateEvent FIRE).
 * FissionMC: Paper threads a BlockStateListPopulator through the private constructor and the calculate* helpers; here the snapshot is built from the shape's
 * geometry (portal interior + frame ring, like Paper's list which "includes the portal frame") at the time the portal is created.
 * Not ported: the causing entity (Paper passes the player from BaseFireBlock/flint and steel); the event gets a null entity.
 */
@Mixin(PortalShape.class)
public abstract class PortalShapeMixin {
    @Shadow @Final private Direction.Axis axis;
    @Shadow @Final private Direction rightDir;
    @Shadow @Final private BlockPos bottomLeft;
    @Shadow @Final private int height;
    @Shadow @Final private int width;

    @Inject(method = "createPortalBlocks", at = @At("HEAD"), cancellable = true)
    private void fission$portalCreateEvent(final LevelAccessor level, final CallbackInfo ci) {
        org.bukkit.craftbukkit.util.BlockStateListPopulator blocks = new org.bukkit.craftbukkit.util.BlockStateListPopulator(level.getMinecraftWorld());
        for (int h = -1; h <= this.height; h++) {
            for (int w = -1; w <= this.width; w++) {
                BlockPos pos = this.bottomLeft.relative(Direction.UP, h).relative(this.rightDir, w);
                boolean frame = h == -1 || h == this.height || w == -1 || w == this.width;
                if (frame) {
                    blocks.setBlock(pos, level.getBlockState(pos), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
        BlockState portalState = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, this.axis);
        BlockPos.betweenClosed(this.bottomLeft, this.bottomLeft.relative(Direction.UP, this.height - 1).relative(this.rightDir, this.width - 1))
            .forEach(pos -> blocks.setBlock(pos, portalState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        org.bukkit.event.world.PortalCreateEvent event = new org.bukkit.event.world.PortalCreateEvent((java.util.List<org.bukkit.block.BlockState>) (java.util.List) blocks.getSnapshotBlocks(), level.getMinecraftWorld().getWorld(), null, org.bukkit.event.world.PortalCreateEvent.CreateReason.FIRE);
        level.getMinecraftWorld().getServer().server.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }
}
