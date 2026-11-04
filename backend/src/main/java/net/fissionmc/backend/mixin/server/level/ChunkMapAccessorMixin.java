package net.fissionmc.backend.mixin.server.level;

import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** FissionMC: access to the protected ChunkMap#tick(BooleanSupplier) for ServerChunkCacheMixin#purgeUnload. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessorMixin {
    @Invoker("tick")
    void fission$tick(BooleanSupplier haveTime);
}
