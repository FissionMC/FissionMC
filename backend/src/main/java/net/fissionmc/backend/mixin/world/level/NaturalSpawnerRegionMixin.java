package net.fissionmc.backend.mixin.world.level;

import java.util.List;
import net.fissionmc.backend.relay.RegionsBackend;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FissionMC static regions: no natural spawning in ghost chunks (chunks another backend owns). New file. */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerRegionMixin {
    @Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
    private static void fission$noSpawnsInGhosts(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState state, List<MobCategory> categories, CallbackInfo ci) {
        if (!RegionsBackend.ownsChunk(level, chunk.getPos().x(), chunk.getPos().z())) {
            RegionsBackend.ghostSpawnPassesSkipped.incrementAndGet();
            ci.cancel();
        }
    }
}
