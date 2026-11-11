package net.fissionmc.backend.mixin.world.level.portal;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.PortalForcer;
import org.bukkit.craftbukkit.util.BlockStateListPopulator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/level/portal/PortalForcer.java.patch (search radius overload, create radius + entity, PortalCreateEvent NETHER_PAIR through a BlockStateListPopulator).
 * Not ported: Paper config hunks (nether ceiling damage height, allowPermanentBlockBreakExploits).
 */
@Mixin(PortalForcer.class)
public abstract class PortalForcerMixin {
    @Shadow @Final private ServerLevel level;
    private Entity fission$entity;
    private int fission$createRadius = 16;
    private BlockStateListPopulator fission$blockList;

    // CraftBukkit start
    public Optional<BlockPos> findClosestPortalPosition(final BlockPos approximateExitPos, final WorldBorder worldBorder, final int radius) {
        PoiManager poiManager = this.level.getPoiManager();
        poiManager.ensureLoadedAndValid(this.level, approximateExitPos, radius);
        return poiManager.getInSquare(type -> type.is(PoiTypes.NETHER_PORTAL), approximateExitPos, radius, PoiManager.Occupancy.ANY)
            .map(PoiRecord::getPos)
            .filter(worldBorder::isWithinBounds)
            .filter(pos -> this.level.getBlockState(pos).hasProperty(BlockStateProperties.HORIZONTAL_AXIS))
            .min(Comparator.<BlockPos>comparingDouble(p -> p.distSqr(approximateExitPos)).thenComparingInt(Vec3i::getY));
    }

    public Optional<BlockUtil.FoundRectangle> createPortal(final BlockPos origin, final Direction.Axis portalAxis, final Entity entity, final int createRadius) {
        this.fission$entity = entity;
        this.fission$createRadius = createRadius;
        try {
            return ((PortalForcer) (Object) this).createPortal(origin, portalAxis);
        } finally {
            this.fission$entity = null;
            this.fission$createRadius = 16;
        }
    }

    @ModifyArg(method = "createPortal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction$Axis;)Ljava/util/Optional;", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;spiralAround(Lnet/minecraft/core/BlockPos;ILnet/minecraft/core/Direction;Lnet/minecraft/core/Direction;)Ljava/lang/Iterable;"), index = 1)
    private int fission$createRadius(final int radius) {
        return this.fission$createRadius;
    }

    @Inject(method = "createPortal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction$Axis;)Ljava/util/Optional;", at = @At("HEAD"))
    private void fission$startList(final BlockPos origin, final Direction.Axis portalAxis, final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Optional<BlockUtil.FoundRectangle>> cir) {
        this.fission$blockList = new BlockStateListPopulator(this.level); // CraftBukkit - Use BlockStateListPopulator
    }

    @WrapOperation(method = "createPortal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction$Axis;)Ljava/util/Optional;", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean fission$listSetBlockAndUpdate(final ServerLevel level, final BlockPos pos, final BlockState state, final Operation<Boolean> original) {
        return this.fission$blockList != null ? this.fission$blockList.setBlockAndUpdate(pos, state) : original.call(level, pos, state);
    }

    @WrapOperation(method = "createPortal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction$Axis;)Ljava/util/Optional;", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean fission$listSetBlock(final ServerLevel level, final BlockPos pos, final BlockState state, final int flags, final Operation<Boolean> original) {
        return this.fission$blockList != null ? this.fission$blockList.setBlock(pos, state, flags) : original.call(level, pos, state, flags);
    }

    @ModifyReturnValue(method = "createPortal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction$Axis;)Ljava/util/Optional;", at = @At("RETURN"))
    private Optional<BlockUtil.FoundRectangle> fission$portalCreateEvent(final Optional<BlockUtil.FoundRectangle> result) {
        BlockStateListPopulator blockList = this.fission$blockList;
        this.fission$blockList = null;
        if (blockList == null || result.isEmpty()) {
            return result;
        }
        org.bukkit.World bworld = this.level.getWorld();
        org.bukkit.event.world.PortalCreateEvent event = new org.bukkit.event.world.PortalCreateEvent((java.util.List<org.bukkit.block.BlockState>) (java.util.List) blockList.getSnapshotBlocks(), bworld, this.fission$entity == null ? null : this.fission$entity.getBukkitEntity(), org.bukkit.event.world.PortalCreateEvent.CreateReason.NETHER_PAIR);
        this.level.getCraftServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return Optional.empty();
        }
        blockList.placeBlocks();
        return result;
    }
    // CraftBukkit end
}
