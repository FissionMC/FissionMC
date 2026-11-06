package net.fissionmc.backend.mixin.server.level;

import net.fissionmc.backend.relay.RegionsBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FissionMC static regions: a backend ticks and spawns only in the chunks it owns. Chunks of another backend's region that
 * are loaded here (the view margin of a border, players near it) are ghosts: random ticks, scheduled block and fluid ticks and entity ticks are skipped; the same goes
 * for natural spawning (see {@link NaturalSpawnerRegionMixin}). New file; with no region map {@link RegionsBackend#ownsChunk} is always true.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelRegionMixin {
    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void fission$ghostChunkTick(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        if (!RegionsBackend.ownsChunk((ServerLevel) (Object) this, chunk.getPos().x(), chunk.getPos().z())) {
            RegionsBackend.ghostChunkTicksSkipped.incrementAndGet();
            ci.cancel();
        } else {
            RegionsBackend.ownedChunkTicks.incrementAndGet();
        }
    }

    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void fission$ghostEntityTick(Entity entity, CallbackInfo ci) {
        if (!RegionsBackend.ownsEntity(entity)) {
            RegionsBackend.ghostEntityTicksSkipped.incrementAndGet();
            ci.cancel();
        }
    }

    @Inject(method = "tickBlock", at = @At("HEAD"), cancellable = true)
    private void fission$ghostBlockTick(BlockPos pos, Block block, CallbackInfo ci) {
        if (!RegionsBackend.ownsChunk((ServerLevel) (Object) this, pos.getX() >> 4, pos.getZ() >> 4)) {
            RegionsBackend.ghostBlockTicksSkipped.incrementAndGet();
            ci.cancel();
        }
    }

    @Inject(method = "tickFluid", at = @At("HEAD"), cancellable = true)
    private void fission$ghostFluidTick(BlockPos pos, Fluid fluid, CallbackInfo ci) {
        if (!RegionsBackend.ownsChunk((ServerLevel) (Object) this, pos.getX() >> 4, pos.getZ() >> 4)) {
            RegionsBackend.ghostBlockTicksSkipped.incrementAndGet();
            ci.cancel();
        }
    }
}
