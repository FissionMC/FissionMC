package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: CampfireBlock.java.patch - BlockIgniteEvent when a burning projectile lights a campfire. */
@Mixin(CampfireBlock.class)
public abstract class CampfireBlockMixin {
    @Inject(method = "onProjectileHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"), cancellable = true)
    private void paper$igniteEvent(final Level level, final BlockState state, final BlockHitResult blockHit, final Projectile projectile, final CallbackInfo ci) {
        if (CraftEventFactory.callBlockIgniteEvent(level, blockHit.getBlockPos(), projectile).isCancelled()) { // CraftBukkit
            ci.cancel();
        }
    }
}
