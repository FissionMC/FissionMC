package net.fissionmc.backend.mixin.world.level.chunk;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** FissionMC: access to protected ChunkAccess members for ported Paper hunks in other classes. */
@Mixin(ChunkAccess.class)
public interface ChunkAccessAccessorMixin {
    @Accessor("pendingBlockEntities")
    Map<BlockPos, CompoundTag> fission$pendingBlockEntities();
}
