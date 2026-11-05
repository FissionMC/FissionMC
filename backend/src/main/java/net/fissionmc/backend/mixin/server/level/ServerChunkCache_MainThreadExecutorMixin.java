package net.fissionmc.backend.mixin.server.level;

import net.fissionmc.backend.world.ChunkMapBridge;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/server/level/ServerChunkCache.java.patch (MainThreadExecutor) */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
public abstract class ServerChunkCache_MainThreadExecutorMixin {
    @Shadow @Final ServerChunkCache this$0;

    // CraftBukkit start - process pending Chunk loadCallback() and unloadCallback() after each run task
    // FissionMC: Paper uses try/finally; this runs on every normal return
    @Inject(method = "pollTask", at = @At("RETURN"))
    private void paper$runChunkCallbacks(final CallbackInfoReturnable<Boolean> cir) {
        ((ChunkMapBridge) this.this$0.chunkMap).fission$callbackExecutor().run();
    }
    // CraftBukkit end - process pending Chunk loadCallback() and unloadCallback() after each run task
}
