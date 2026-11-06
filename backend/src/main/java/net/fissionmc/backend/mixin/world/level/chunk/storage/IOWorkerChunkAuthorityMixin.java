package net.fissionmc.backend.mixin.world.level.chunk.storage;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.fissionmc.backend.chunk.ChunkAuthorityBackend;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FissionMC chunk authority: {@code IOWorker} is the single read/write funnel of the chunk, entities and poi region
 * storages. Reads are completed with the master's data (asynchronously), writes are queued for the master. New file; nothing of the ported world code is touched.
 */
@Mixin(IOWorker.class)
public abstract class IOWorkerChunkAuthorityMixin {
    @org.spongepowered.asm.mixin.Shadow public abstract RegionStorageInfo storageInfo();
    @org.spongepowered.asm.mixin.Shadow public abstract CompletableFuture<Void> store(ChunkPos pos, CompoundTag value);

    @WrapMethod(method = "loadAsync")
    private CompletableFuture<Optional<CompoundTag>> fission$load(ChunkPos pos, Operation<CompletableFuture<Optional<CompoundTag>>> original) {
        RegionStorageInfo info = this.storageInfo();
        int kind = ChunkAuthorityBackend.kindOf(info.type());
        CompletableFuture<Optional<CompoundTag>> local = original.call(pos);
        if (kind < 0) {
            return local;
        }
        String dim = info.dimension().identifier().toString();
        return local.thenCompose(l -> ChunkAuthorityBackend.resolve(dim, kind, pos.x(), pos.z(), l, tag -> {
            ChunkAuthorityBackend.skipPush(true);   // the master's own chunk, written locally as a cache: not pushed back
            try {
                this.store(pos, tag);
            } finally {
                ChunkAuthorityBackend.skipPush(false);
            }
        }));
    }

    @WrapMethod(method = "store(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;")
    private CompletableFuture<Void> fission$store(ChunkPos pos, Supplier<CompoundTag> supplier, Operation<CompletableFuture<Void>> original) {
        RegionStorageInfo info = this.storageInfo();
        int kind = ChunkAuthorityBackend.kindOf(info.type());
        if (kind < 0 || !ChunkAuthorityBackend.pushing()) {
            return original.call(pos, supplier);
        }
        String dim = info.dimension().identifier().toString();
        Supplier<CompoundTag> wrapped = () -> {
            CompoundTag tag = supplier.get();
            if (tag != null) {
                ChunkAuthorityBackend.onStore(dim, kind, pos.x(), pos.z(), tag);
            }
            return tag;
        };
        return original.call(pos, wrapped);
    }

    @WrapMethod(method = "scanChunk")
    private CompletableFuture<Void> fission$scan(ChunkPos pos, StreamTagVisitor visitor, Operation<CompletableFuture<Void>> original) {
        RegionStorageInfo info = this.storageInfo();
        if (ChunkAuthorityBackend.kindOf(info.type()) != 0) {
            return original.call(pos, visitor);
        }
        // structure checks scan neighbours that are not loaded: use the master's chunk if it has one, never take a generation lease for a mere look
        String dim = info.dimension().identifier().toString();
        return ChunkAuthorityBackend.peek(dim, 0, pos.x(), pos.z()).thenCompose(opt -> {
            if (opt.isPresent()) {
                try {
                    opt.get().acceptAsRoot(visitor);
                    return CompletableFuture.<Void>completedFuture(null);
                } catch (Exception e) {
                    return CompletableFuture.<Void>failedFuture(e);
                }
            }
            return original.call(pos, visitor);
        });
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void fission$flushOnClose(CallbackInfo ci) {
        ChunkAuthorityBackend.flush(5000L);
    }
}
