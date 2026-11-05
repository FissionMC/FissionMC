package net.fissionmc.backend.mixin.server.level;

import com.llamalad7.mixinextras.sugar.Local;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.fissionmc.backend.world.ChunkHolderBridge;
import net.fissionmc.backend.world.ChunkMapBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/server/level/ChunkHolder.java.patch */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin implements ChunkHolderBridge {
    @Shadow public int oldTicketLevel;
    @Shadow private int ticketLevel;
    @Shadow @Final private LevelHeightAccessor levelHeightAccessor;
    @Shadow @Final private it.unimi.dsi.fastutil.shorts.ShortSet[] changedBlocksPerSection;
    @Shadow public abstract CompletableFuture<ChunkResult<LevelChunk>> getFullChunkFuture();

    // CraftBukkit start
    public LevelChunk getFullChunkNow() {
        // Note: We use the oldTicketLevel for isLoaded checks.
        if (!ChunkLevel.fullStatus(this.oldTicketLevel).isOrAfter(FullChunkStatus.FULL)) return null;
        return this.getFullChunkNowUnchecked();
    }

    public LevelChunk getFullChunkNowUnchecked() {
        return (LevelChunk) ((ChunkHolder) (Object) this).getChunkIfPresentUnchecked(ChunkStatus.FULL);
    }
    // CraftBukkit end

    @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
    private void paper$sectionBoundsCheck(final BlockPos pos, final CallbackInfoReturnable<Boolean> cir) {
        int sectionIndex = this.levelHeightAccessor.getSectionIndex(pos.getY());
        if (sectionIndex < 0 || sectionIndex >= this.changedBlocksPerSection.length) cir.setReturnValue(false); // CraftBukkit - SPIGOT-6086, SPIGOT-6296
    }

    // CraftBukkit start
    // ChunkUnloadEvent: Called before the chunk is unloaded: isChunkLoaded is still true and chunk can still be modified by plugins.
    // SPIGOT-7780: Moved out of updateFutures to call all chunk unload events before calling updateHighestAllowedStatus for all chunks
    @Override
    public void fission$callEventIfUnloading(ChunkMap chunkMap) {
        FullChunkStatus oldFullChunkStatus = ChunkLevel.fullStatus(this.oldTicketLevel);
        FullChunkStatus newFullChunkStatus = ChunkLevel.fullStatus(this.ticketLevel);
        boolean oldIsFull = oldFullChunkStatus.isOrAfter(FullChunkStatus.FULL);
        boolean newIsFull = newFullChunkStatus.isOrAfter(FullChunkStatus.FULL);
        if (oldIsFull && !newIsFull) {
            final net.fissionmc.backend.world.ChunkCallbackExecutor callbackExecutor = ((ChunkMapBridge) chunkMap).fission$callbackExecutor();
            this.getFullChunkFuture().thenAccept((either) -> {
                LevelChunk chunk = either.orElse(null);
                if (chunk != null) {
                    callbackExecutor.execute(() -> {
                        // Minecraft will apply the chunks tick lists to the world once the chunk got loaded, and then store the tick
                        // lists again inside the chunk once the chunk becomes inaccessible and set the chunk's needsSaving flag.
                        // These actions may however happen deferred, so we manually set the needsSaving flag already here.
                        chunk.markUnsaved();
                        chunk.unloadCallback();
                    });
                }
            }).exceptionally((throwable) -> {
                // ensure exceptions are printed, by default this is not the case
                net.minecraft.server.MinecraftServer.LOGGER.error("Failed to schedule unload callback for chunk " + ((ChunkHolder) (Object) this).getPos(), throwable);
                return null;
            });

            // Run callback right away if the future was already done
            callbackExecutor.run();
        }
    }
    // CraftBukkit end

    // TODO(moonrise): Paper's "cache ticking ready status" hunks (isFullChunkReady/isTickingReady/isEntityTickingReady and the
    //  PlatformHooks onChunkBorder/onChunkTicking/... callbacks) belong to the Moonrise chunk system and are not ported.
    @Inject(method = "updateFutures", at = @At("TAIL"))
    private void paper$callLoadEvent(final ChunkMap scheduler, final Executor mainThreadExecutor, final CallbackInfo ci,
                                     @Local(ordinal = 0) final FullChunkStatus oldFullStatus, @Local(ordinal = 1) final FullChunkStatus newFullStatus) {
        // CraftBukkit start
        // ChunkLoadEvent: Called after the chunk is loaded: isChunkLoaded returns true and chunk is ready to be modified by plugins.
        if (!oldFullStatus.isOrAfter(FullChunkStatus.FULL) && newFullStatus.isOrAfter(FullChunkStatus.FULL)) {
            final net.fissionmc.backend.world.ChunkCallbackExecutor callbackExecutor = ((ChunkMapBridge) scheduler).fission$callbackExecutor();
            this.getFullChunkFuture().thenAccept((either) -> {
                LevelChunk chunk = (LevelChunk) either.orElse(null);
                if (chunk != null) {
                    callbackExecutor.execute(() -> {
                        chunk.loadCallback();
                    });
                }
            }).exceptionally((throwable) -> {
                // ensure exceptions are printed, by default this is not the case
                net.minecraft.server.MinecraftServer.LOGGER.error("Failed to schedule load callback for chunk " + ((ChunkHolder) (Object) this).getPos(), throwable);
                return null;
            });

            // Run callback right away if the future was already done
            callbackExecutor.run();
        }
        // CraftBukkit end
    }
}
