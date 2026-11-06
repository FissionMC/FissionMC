package net.fissionmc.backend.mixin.world.level;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.fissionmc.backend.replication.Replication;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * FissionMC replication: reports block changes that actually happened on a ServerLevel (thin shim, logic is Kotlin).
 * A WrapMethod instead of a RETURN inject: the Paper port's LevelMixin ({@code paper$setBlock}, HEAD + setReturnValue) returns early with its own result, and a RETURN
 * injector never sees those returns, so after the port no block change was replicated at all (found by ).
 */
@Mixin(Level.class)
public abstract class LevelReplicationMixin {
    @WrapMethod(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean fission$replicateSetBlock(BlockPos pos, BlockState state, int flags, int depth, Operation<Boolean> original) {
        boolean changed = original.call(pos, state, flags, depth);
        if (changed && (Object) this instanceof ServerLevel) {
            Replication.onSetBlock((Level) (Object) this, pos, state);
        }
        return changed;
    }
}
