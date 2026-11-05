package net.fissionmc.backend.mixin.server.level;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import java.util.Set;
import net.fissionmc.backend.world.ChunkHolderBridge;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/server/level/DistanceManager.java.patch */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {
    @Shadow @Final private Set<ChunkHolder> chunksToUpdateFutures;

    @Inject(method = "runAllUpdates", at = @At(value = "INVOKE", target = "Ljava/util/Set;iterator()Ljava/util/Iterator;", ordinal = 0))
    private void paper$callUnloadEvents(final ChunkMap scheduler, final CallbackInfoReturnable<Boolean> cir) {
        // CraftBukkit start - SPIGOT-7780: Call chunk unload events before updateHighestAllowedStatus
        for (final ChunkHolder chunksToUpdateFuture : this.chunksToUpdateFutures) {
            ((ChunkHolderBridge) chunksToUpdateFuture).fission$callEventIfUnloading(scheduler);
        }
        // CraftBukkit end - SPIGOT-7780: Call chunk unload events before updateHighestAllowedStatus
    }

    // Paper start - some state corruption happens here, don't crash, clean up gracefully
    @WrapOperation(method = "removePlayer", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectSet;remove(Ljava/lang/Object;)Z"))
    private boolean paper$nullSafeRemove(final ObjectSet<?> chunkPlayers, final Object player, final Operation<Boolean> original) {
        return chunkPlayers != null && original.call(chunkPlayers, player);
    }

    @WrapOperation(method = "removePlayer", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectSet;isEmpty()Z"))
    private boolean paper$nullSafeIsEmpty(final ObjectSet<?> chunkPlayers, final Operation<Boolean> original) {
        return chunkPlayers == null || original.call(chunkPlayers);
    }
    // Paper end - some state corruption happens here, don't crash, clean up gracefully
}
