package net.fissionmc.backend.mixin.world.entity.monster;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/monster/Enderman.java.patch (EndermanTakeBlockGoal: EntityChangeBlockEvent) */
@Mixin(targets = "net.minecraft.world.entity.monster.Enderman$EndermanTakeBlockGoal")
public abstract class Enderman_EndermanTakeBlockGoalMixin {
    @Shadow @Final private Enderman enderman;

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"), cancellable = true)
    private void fission$takeEvent(final CallbackInfo ci, @Local(name = "pos") BlockPos pos, @Local(name = "blockState") BlockState blockState) {
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(this.enderman, pos, blockState.getFluidState().createLegacyBlock())) { // Paper - Place event
            ci.cancel();
        }
    }
}
