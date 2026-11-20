package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/ContainerOpenersCounter.java.patch
 * Done: BlockRedstoneEvent for trapped chests (wraps openerCountChanged in increment/decrement/recheck), recheckOpeners counts `opened` as a dummy
 * opener, decrementOpeners never goes negative.
 */
@Mixin(ContainerOpenersCounter.class)
public abstract class ContainerOpenersCounterMixin {
    @Shadow protected abstract void onOpen(final Level level, final BlockPos pos, final BlockState blockState);
    @Shadow protected abstract void onClose(final Level level, final BlockPos pos, final BlockState blockState);
    @Shadow protected abstract void openerCountChanged(final Level level, final BlockPos pos, final BlockState blockState, final int previous, final int current);

    // CraftBukkit start
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private boolean opened;

    public void onOpenAPI(Level level, BlockPos pos, BlockState blockState) {
        this.onOpen(level, pos, blockState);
    }

    public void onCloseAPI(Level level, BlockPos pos, BlockState blockState) {
        this.onClose(level, pos, blockState);
    }

    public void openerCountChangedAPI(Level level, BlockPos pos, BlockState blockState, int previous, int current) {
        this.openerCountChanged(level, pos, blockState, previous, current);
    }
    // CraftBukkit end

    @Shadow private int openCount;

    // Paper - Prevent ContainerOpenersCounter openCount from going negative
    @Inject(method = "decrementOpeners", at = @At("HEAD"), cancellable = true)
    private void paper$noNegativeCount(final net.minecraft.world.entity.LivingEntity entity, final Level level, final BlockPos pos, final BlockState blockState, final CallbackInfo ci) {
        if (this.openCount == 0) {
            ci.cancel();
        }
    }

    // CraftBukkit - add dummy count from API
    @WrapOperation(method = "recheckOpeners", at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"))
    private int paper$dummyOpener(final java.util.List<?> users, final Operation<Integer> original) {
        return original.call(users) + (this.opened ? 1 : 0);
    }

    // Paper - Call BlockRedstoneEvent: trapped chests emit a signal equal to the opener count
    @WrapOperation(method = {"incrementOpeners", "decrementOpeners", "recheckOpeners"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/ContainerOpenersCounter;openerCountChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)V"))
    private void paper$redstoneEvent(final ContainerOpenersCounter self, final Level level, final BlockPos pos, final BlockState blockState, final int previous, final int current, final Operation<Void> original) {
        if (blockState.is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST)) {
            final int oldCurrent = Math.clamp(previous, 0, 15);
            final int newCurrent = Math.clamp(current, 0, 15);
            if (oldCurrent != newCurrent) {
                org.bukkit.craftbukkit.event.CraftEventFactory.callRedstoneChange(level, pos, oldCurrent, newCurrent);
            }
        }
        original.call(self, level, pos, blockState, previous, current);
    }
}
