package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/level/block/VineBlock.java.patch (BlockGrowEvent/BlockSpreadEvent; config: Spigot vineModifier) */
@Mixin(VineBlock.class)
public abstract class VineBlockMixin {
    // Every level.setBlock in randomTick: growth in place -> BlockGrowEvent, otherwise BlockSpreadEvent from the vine
    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean fission$events(final ServerLevel level, final BlockPos target, final BlockState newState, final int flags, final Operation<Boolean> original, final BlockState state, final ServerLevel level2, final BlockPos pos, final RandomSource random) {
        if (target.equals(pos)) {
            return org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockGrowEvent(level, pos, newState, flags); // CraftBukkit
        }
        return org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockSpreadEvent(level, pos, target, newState, flags); // CraftBukkit
    }
}
