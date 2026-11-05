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

/** Paper: sources/net/minecraft/world/level/block/DoorBlock.java.patch */
@SuppressWarnings("unused")
@Mixin(DoorBlock.class)
public abstract class DoorBlockMixin {
    @Inject(method = "neighborChanged", at = @At("HEAD"), cancellable = true)
    private void fission$redstone(final BlockState state, final Level level, final BlockPos pos, final Block block, final @Nullable Orientation orientation, final boolean movedByPiston, final CallbackInfo ci) {
        // Paper start - Call BlockRedstoneEvent (FissionMC: condition recomputed at HEAD, vanilla reads are side-effect free)
        boolean signal = level.hasNeighborSignal(pos) || level.hasNeighborSignal(pos.relative(state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? net.minecraft.core.Direction.UP : net.minecraft.core.Direction.DOWN));
        if (!((Block) (Object) this).defaultBlockState().is(block) && signal != state.getValue(DoorBlock.POWERED)) {
            if (!CraftEventFactory.callBinaryRedstoneChange(level, pos, signal)) {
                ci.cancel();
                return;
            }
        }
        // Paper end - Call BlockRedstoneEvent
    }
}
