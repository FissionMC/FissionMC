package net.fissionmc.backend.mixin.world.level.block.entity;

import net.fissionmc.backend.relay.SignalSyncBackend;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ComparatorBlockEntity.class)
public abstract class ComparatorBlockEntityMixin {
    @Inject(method = "setOutputSignal", at = @At("TAIL"))
    private void fission$syncOutput(int output, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() instanceof ServerLevel level) {
            SignalSyncBackend.onOutput(level, self.getBlockPos(), output);
        }
    }
}
