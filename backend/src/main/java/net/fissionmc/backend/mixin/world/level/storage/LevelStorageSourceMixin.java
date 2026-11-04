package net.fissionmc.backend.mixin.world.level.storage;

import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/storage/LevelStorageSource.java.patch */
@Mixin(LevelStorageSource.class)
public abstract class LevelStorageSourceMixin {
    // TODO(behaviour): getLevelDataAndDimensions hunk (pass dimension, regenerate missing world gen settings)
    private static <T extends SavedData> DataResult<T> readExistingSavedData(
        final LevelStorageSource.LevelStorageAccess access, final ResourceKey<Level> dimension, final HolderLookup.Provider registryAccess, final SavedDataType<T> savedDataType // Paper - pass dimension
    ) {
        Path dataLocation = savedDataType.id().withSuffix(".dat").resolveAgainst(access.getDimensionPath(dimension).resolve(LevelResource.DATA.id())); // Paper - use dimension scoped data (we 'demote' some data from global and add our own dimension scoped data)

        CompoundTag fileContents;
        try {
            fileContents = NbtIo.readCompressed(dataLocation, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            return DataResult.error(e::getMessage);
        }

        return savedDataType.codec().parse(RegistryOps.create(NbtOps.INSTANCE, registryAccess), fileContents.getCompoundOrEmpty("data"));
    }
}
