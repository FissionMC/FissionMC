package net.fissionmc.backend.mixin.world.level;

import net.fissionmc.backend.replication.Replication;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** FissionMC replication: reports block changes that actually happened on a ServerLevel (thin shim, logic is Kotlin). */
@Mixin(Level.class)
public abstract class LevelReplicationMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void fission$replicateSetBlock(BlockPos pos, BlockState state, int flags, int depth, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && (Object) this instanceof ServerLevel) {
            Replication.onSetBlock((Level) (Object) this, pos, state);
        }
    }
}
