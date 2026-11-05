package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.redstone.Orientation;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/level/block/TrapDoorBlock.java.patch */
@SuppressWarnings("unused")
@Mixin(TrapDoorBlock.class)
public abstract class TrapDoorBlockMixin {
    @Inject(method = "neighborChanged", at = @At("HEAD"), cancellable = true)
    private void fission$redstone(final BlockState state, final Level level, final BlockPos pos, final Block block, final @Nullable Orientation orientation, final boolean movedByPiston, final CallbackInfo ci) {
        // Paper start - Call BlockRedstoneEvent (FissionMC: condition recomputed at HEAD, vanilla reads are side-effect free)
        if (level.isClientSide()) return;
        boolean signal = level.hasNeighborSignal(pos);
        if (signal != state.getValue(BlockStateProperties.POWERED)) {
            if (!CraftEventFactory.callBinaryRedstoneChange(level, pos, signal)) {
                ci.cancel();
                return;
            }
            // Paper start - break redstone on trapdoors early
            boolean open = state.getValue(TrapDoorBlock.OPEN) != signal;
            // note: this must run before any state for this block/its neighbours are written to the world
            // we allow the redstone event to fire so that plugins can block
            if (signal && open) { // if we are now powered and it caused the trap door to open
                // in this case, first check for the redstone on top first
                BlockPos abovePos = pos.above();
                BlockState above = level.getBlockState(abovePos);
                if (above.getBlock() instanceof RedstoneWireBlock) {
                    level.setBlock(abovePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
                    Block.popResource(level, abovePos, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.REDSTONE));
                    // now check that this didn't change our state
                    if (level.getBlockState(pos) != state) {
                        // our state was changed, so we cannot propagate this update
                        ci.cancel();
                    }
                }
            }
            // Paper end - break redstone on trapdoors early
        }
        // Paper end - Call BlockRedstoneEvent
    }
}
