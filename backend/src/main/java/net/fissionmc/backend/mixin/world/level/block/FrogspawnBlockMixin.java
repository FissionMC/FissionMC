package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FrogspawnBlock;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: FrogspawnBlock.java.patch - BlockFadeEvent when the frogspawn hatches. */
@Mixin(FrogspawnBlock.class)
public abstract class FrogspawnBlockMixin {
    @Inject(method = "hatchFrogspawn", at = @At("HEAD"), cancellable = true)
    private void paper$fade(final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci) {
        if (CraftEventFactory.callBlockFadeEvent(level, pos, Blocks.AIR.defaultBlockState()).isCancelled()) { // Paper - Call BlockFadeEvent
            ci.cancel();
        }
    }
}
