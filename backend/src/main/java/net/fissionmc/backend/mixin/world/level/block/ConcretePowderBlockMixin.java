package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.block.CraftBlockStates;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/level/block/ConcretePowderBlock.java.patch (BlockFormEvent on land, placement and neighbour update) */
@Mixin(ConcretePowderBlock.class)
public abstract class ConcretePowderBlockMixin {
    @Shadow
    @Final
    private Block concrete;

    @Shadow
    private static boolean shouldSolidify(final BlockGetter level, final BlockPos pos, final BlockState replacedBlock) {
        throw new AssertionError();
    }

    @Shadow
    private static boolean touchesLiquid(final BlockGetter level, final BlockPos pos) {
        throw new AssertionError();
    }

    @WrapOperation(method = "onLand", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean paper$landForm(final Level level, final BlockPos pos, final BlockState state, final Operation<Boolean> original) {
        return CraftEventFactory.handleBlockFormEvent(level, pos, this.concrete.defaultBlockState(), Block.UPDATE_ALL); // CraftBukkit
    }

    @Inject(method = "getStateForPlacement", at = @At("HEAD"), cancellable = true)
    private void paper$placeForm(final BlockPlaceContext context, final CallbackInfoReturnable<BlockState> cir) {
        final Level level = context.getLevel();
        final BlockPos pos = context.getClickedPos();
        final BlockState replacedBlock = level.getBlockState(pos);
        if (shouldSolidify(level, pos, replacedBlock)) {
            final CraftBlockState snapshot = CraftBlockStates.getBlockState(level, pos);
            snapshot.setBlock(this.concrete.defaultBlockState());
            final org.bukkit.event.block.BlockFormEvent event = new org.bukkit.event.block.BlockFormEvent(snapshot.getBlock(), snapshot);
            event.callEvent();
            if (!event.isCancelled()) {
                cir.setReturnValue(snapshot.getHandle());
            }
            // cancelled: fall through to the plain placement state (Block#getStateForPlacement)
            else {
                cir.setReturnValue(((Block) (Object) this).defaultBlockState());
            }
        }
    }

    @Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
    private void paper$shapeForm(final BlockState state, final LevelReader level, final ScheduledTickAccess ticks, final BlockPos pos, final Direction direction, final BlockPos neighbourPos, final BlockState neighbourState, final RandomSource random, final CallbackInfoReturnable<BlockState> cir) {
        if (touchesLiquid(level, pos)) {
            if (!(level instanceof Level world)) { // Suppress during worldgen
                cir.setReturnValue(this.concrete.defaultBlockState());
                return;
            }
            final CraftBlockState snapshot = CraftBlockStates.getBlockState(world, pos);
            snapshot.setBlock(this.concrete.defaultBlockState());
            final org.bukkit.event.block.BlockFormEvent event = new org.bukkit.event.block.BlockFormEvent(snapshot.getBlock(), snapshot);
            world.getCraftServer().getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                cir.setReturnValue(snapshot.getHandle());
            }
        }
    }
}
