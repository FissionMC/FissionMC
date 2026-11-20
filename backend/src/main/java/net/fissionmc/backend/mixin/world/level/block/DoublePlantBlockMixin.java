package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: DoublePlantBlock.java.patch - BlockPhysicsEvent in preventDropFromBottomPart. */
@Mixin(DoublePlantBlock.class)
public abstract class DoublePlantBlockMixin {
    @Inject(method = "preventDropFromBottomPart", at = @At("HEAD"), cancellable = true)
    private static void paper$physicsEvent(final Level level, final BlockPos pos, final BlockState state, final Player player, final CallbackInfo ci) {
        if (((ServerLevel) level).hasPhysicsEvent && CraftEventFactory.callBlockPhysicsEvent(level, pos).isCancelled()) {
            ci.cancel();
        }
    }
}
