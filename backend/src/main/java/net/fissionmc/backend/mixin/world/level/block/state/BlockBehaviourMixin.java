package net.fissionmc.backend.mixin.world.level.block.state;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/level/block/state/BlockBehaviour.java.patch */
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviourMixin {
    // Paper - Protect Bedrock and End Portal/Frames from being destroyed
    @ModifyExpressionValue(method = "onExplosionHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z"))
    private boolean fission$explosionIndestructible(final boolean isAir, final BlockState state) {
        return isAir || !state.isDestroyable(); // Paper - Protect Bedrock and End Portal/Frames from being destroyed
    }

    // CraftBukkit start - add yield
    @ModifyExpressionValue(method = "onExplosionHit", at = @At(value = "INVOKE", ordinal = 1, target = "Lnet/minecraft/world/level/Explosion;getBlockInteraction()Lnet/minecraft/world/level/Explosion$BlockInteraction;"))
    private Explosion.BlockInteraction fission$yieldCheck(final Explosion.BlockInteraction original, final BlockState state, final net.minecraft.server.level.ServerLevel level, final net.minecraft.core.BlockPos pos, final Explosion explosion) {
        return explosion instanceof net.minecraft.world.level.ServerExplosion serverExplosion && serverExplosion.yield < 1.0F
            ? Explosion.BlockInteraction.DESTROY_WITH_DECAY : Explosion.BlockInteraction.KEEP;
    }

    @ModifyExpressionValue(method = "onExplosionHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Explosion;radius()F"))
    private float fission$yieldRadius(final float original, final BlockState state, final net.minecraft.server.level.ServerLevel level, final net.minecraft.core.BlockPos pos, final Explosion explosion) {
        return 1.0F / ((net.minecraft.world.level.ServerExplosion) explosion).yield;
    }
    // CraftBukkit end

    @ModifyReturnValue(method = "canBeReplaced(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/item/context/BlockPlaceContext;)Z", at = @At("RETURN"))
    private boolean fission$canBeReplaced(final boolean original, final BlockState state, final BlockPlaceContext context) {
        return original && (state.isDestroyable() || (context.getPlayer() != null && context.getPlayer().getAbilities().instabuild)); // Paper - Protect Bedrock and End Portal/Frames from being destroyed
    }

    // TODO(behaviour): AsyncCatcher.catchOp("block onPlace") (Spigot) not ported.
}
