package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/block/LeavesBlock.java.patch (LeavesDecayEvent) */
@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {
    @Inject(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/LeavesBlock;dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"), cancellable = true)
    private void fission$leavesDecay(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci) {
        org.bukkit.event.block.LeavesDecayEvent event = new org.bukkit.event.block.LeavesDecayEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos));
        level.getCraftServer().getPluginManager().callEvent(event);

        if (event.isCancelled() || !level.getBlockState(pos).is((LeavesBlock) (Object) this)) {
            ci.cancel();
        }
    }
}
