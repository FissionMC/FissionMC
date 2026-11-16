package net.fissionmc.backend.mixin.world.level.redstone;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.fissionmc.backend.relay.WireHoldBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DefaultRedstoneWireEvaluator.class)
public abstract class DefaultRedstoneWireEvaluatorMixin {
    @Shadow
    private int calculateTargetStrength(Level level, BlockPos pos) {
        throw new AssertionError();
    }

    @ModifyReturnValue(method = "calculateTargetStrength", at = @At("RETURN"))
    private int fission$holdAfterDrop(int strength, Level level, BlockPos pos) {
        if (level instanceof ServerLevel server) {
            return WireHoldBackend.adjust(server, pos, strength, () -> this.calculateTargetStrength(level, pos));
        }
        return strength;
    }
}
