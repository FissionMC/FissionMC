package net.fissionmc.backend.mixin.world.level.chunk;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/level/chunk/LevelChunk.java.patch (BoundTickingBlockEntity) */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class LevelChunk_BoundTickingBlockEntityMixin {
    @Shadow @Final LevelChunk this$0;
    @Shadow public abstract BlockPos getPos();

    // Paper start - Remove the Block Entity if it's invalid
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntityType;isValid(Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean paper$removeInvalid(final boolean valid) {
        if (!valid) {
            this.this$0.removeBlockEntity(this.getPos());
        }
        return valid;
    }
    // Paper end - Remove the Block Entity if it's invalid

    // Paper start - Prevent block entity and entity crashes
    // FissionMC: Paper replaces the crash in the catch block; wrapping the ticker call catches what the ticker throws
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private void paper$catchTickException(final BlockEntityTicker<BlockEntity> ticker, final Level level, final BlockPos pos, final BlockState state, final BlockEntity blockEntity, final Operation<Void> original) {
        try {
            original.call(ticker, level, pos, state, blockEntity);
        } catch (Throwable t) {
            final String msg = String.format("BlockEntity threw exception at %s:%s,%s,%s", io.papermc.paper.util.MCUtil.getLevelName(this.this$0.getLevel()), this.getPos().getX(), this.getPos().getY(), this.getPos().getZ());
            org.slf4j.LoggerFactory.getLogger(LevelChunk.class).error(msg, t);
            ((net.minecraft.server.level.ServerLevel) this.this$0.getLevel()).getCraftServer().getPluginManager().callEvent(new com.destroystokyo.paper.event.server.ServerExceptionEvent(new com.destroystokyo.paper.exception.ServerInternalException(msg, t))); // Paper - ServerExceptionEvent
            this.this$0.removeBlockEntity(this.getPos());
        }
    }
    // Paper end - Prevent block entity and entity crashes
}
