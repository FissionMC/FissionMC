package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fissionmc.backend.blocks.PaperContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.component.Compostable;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/block/ComposterBlock.java.patch (InputContainer.setChanged: compost events, see ComposterBlockMixin). */
@Mixin(targets = "net.minecraft.world.level.block.ComposterBlock$InputContainer")
public abstract class ComposterBlock_InputContainerMixin {
    // Paper start - call compost events
    @WrapOperation(method = "setChanged", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/ComposterBlock;addLayer(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/component/Compostable;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState paper$stack(final Entity sourceEntity, final BlockState state, final ServerLevel level, final BlockPos pos, final Compostable compostable, final Operation<BlockState> original) {
        PaperContext.COMPOST_STACK.set(((net.minecraft.world.SimpleContainer) (Object) this).getItem(0));
        try {
            return original.call(sourceEntity, state, level, pos, compostable);
        } finally {
            PaperContext.COMPOST_STACK.remove();
        }
    }

    @Inject(method = "setChanged", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/world/level/block/ComposterBlock;addLayer(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/component/Compostable;)Lnet/minecraft/world/level/block/state/BlockState;"), cancellable = true)
    private void paper$cancelled(final CallbackInfo ci) {
        if (PaperContext.takeCompostCancelled()) {
            ci.cancel();
        }
    }
    // Paper end - call compost events
}
