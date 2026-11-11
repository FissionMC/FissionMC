package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/block/ComposterBlock.java.patch (OutputContainer.setChanged). */
@Mixin(targets = "net.minecraft.world.level.block.ComposterBlock$OutputContainer")
public abstract class ComposterBlock_OutputContainerMixin {
    @Shadow @Final private BlockState state;
    @Shadow @Final private LevelAccessor level;
    @Shadow @Final private BlockPos pos;
    @Shadow private boolean changed;

    // CraftBukkit start - allow putting items back (eg cancelled InventoryMoveItemEvent)
    @Inject(method = "setChanged", at = @At("HEAD"), cancellable = true)
    private void paper$putBack(final CallbackInfo ci) {
        if (!((net.minecraft.world.SimpleContainer) (Object) this).isEmpty()) {
            this.level.setBlock(this.pos, this.state, Block.UPDATE_ALL);
            this.changed = false;
            ci.cancel();
        }
    }
    // CraftBukkit end
}
