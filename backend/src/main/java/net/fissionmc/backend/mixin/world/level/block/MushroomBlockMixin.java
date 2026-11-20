package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fissionmc.backend.blocks.PaperGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: MushroomBlock.java.patch - BlockSpreadEvent when mushrooms spread (source = the ticking block, not the reassigned pos). */
@Mixin(MushroomBlock.class)
public abstract class MushroomBlockMixin {
    @Inject(method = "randomTick", at = @At("HEAD"))
    private void paper$recordSource(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci) {
        PaperGrowth.mushroomSource = pos;
    }

    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean paper$spread(final ServerLevel level, final BlockPos offset, final BlockState state, final int flags, final Operation<Boolean> original) {
        final BlockPos source = PaperGrowth.mushroomSource;
        PaperGrowth.mushroomSource = null;
        return CraftEventFactory.handleBlockSpreadEvent(level, source != null ? source : offset, offset, state, flags); // CraftBukkit
    }
}
