package net.fissionmc.backend.mixin.world.level.redstone;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.fissionmc.backend.relay.WireHoldBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RedstoneWireEvaluator.class)
public abstract class RedstoneWireEvaluatorMixin {
    @ModifyReturnValue(method = "getWireSignal", at = @At("RETURN"))
    private int fission$ignoreGhostWire(int signal, BlockPos pos, BlockState state) {
        return WireHoldBackend.ignoringGhosts(pos) ? 0 : signal;
    }
}
