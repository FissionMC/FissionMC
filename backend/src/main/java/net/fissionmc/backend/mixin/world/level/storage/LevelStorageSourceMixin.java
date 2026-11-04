package net.fissionmc.backend.mixin.world.level.storage;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import java.nio.file.Files;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.PrimaryLevelData;
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
import org.spongepowered.asm.mixin.Shadow;
import org.slf4j.Logger;

/** Paper: sources/net/minecraft/world/level/storage/LevelStorageSource.java.patch */
@Mixin(LevelStorageSource.class)
public abstract class LevelStorageSourceMixin {
    @Shadow @org.spongepowered.asm.mixin.Final private static Logger LOGGER;

    // FissionMC: Paper changes the vanilla 5-argument getLevelDataAndDimensions to take the dimension; Mixin cannot change
    // the signature, so vanilla's overload stays and Paper's two overloads are added (surface: public static). Paper's
    // start-up (MainMixin) calls the 7-argument one.
    private static LevelDataAndDimensions getLevelDataAndDimensions(
        final LevelStorageSource.LevelStorageAccess worldAccess,
        final Dynamic<?> levelDataTag,
        final WorldDataConfiguration dataConfiguration,
        final Registry<LevelStem> datapackDimensions,
        final HolderLookup.Provider registryAccess
        , final ResourceKey<Level> dimension // Paper - pass dimension
    ) {
        // Paper start - Regenerate missing overworld world gen settings
        return getLevelDataAndDimensions(worldAccess, levelDataTag, dataConfiguration, datapackDimensions, registryAccess, dimension, () -> {
            // Preserve dimension settings if we got them from the datapack
            WorldDimensions defaultDimensions = datapackDimensions.containsKey(LevelStem.OVERWORLD)
                ? new WorldDimensions(datapackDimensions)
                : net.minecraft.world.level.levelgen.presets.WorldPresets.createNormalWorldDimensions(registryAccess);
            return new WorldGenSettings(WorldOptions.defaultWithRandomSeed(), defaultDimensions);
        });
    }

    private static LevelDataAndDimensions getLevelDataAndDimensions(
        final LevelStorageSource.LevelStorageAccess worldAccess,
        final Dynamic<?> levelDataTag,
        final WorldDataConfiguration dataConfiguration,
        final Registry<LevelStem> datapackDimensions,
        final HolderLookup.Provider registryAccess,
        final ResourceKey<Level> dimension,
        final java.util.function.Supplier<WorldGenSettings> worldGenSettingsFactory
    ) {
        // Paper end - Regenerate missing overworld world gen settings
        if (DataFixers.getFileFixer().requiresFileFixing(NbtUtils.getDataVersion(levelDataTag))) {
            throw new IllegalStateException("Cannot get level data without file fixing first");
        }

        Dynamic<?> dataTag = RegistryOps.injectRegistryContext(levelDataTag, registryAccess);
        WorldGenSettings worldGenSettings = readExistingSavedData(worldAccess, dimension, registryAccess, WorldGenSettings.TYPE) // Paper - pass dimension
            .mapOrElse(
                Function.identity(),
                error -> {
                    // Paper start - Regenerate missing overworld world gen settings
                    Path dataLocation = WorldGenSettings.TYPE.id().withSuffix(".dat").resolveAgainst(worldAccess.getDimensionPath(dimension).resolve(LevelResource.DATA.id()));
                    if (!Files.notExists(dataLocation, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalStateException("Unable to read or access the world gen settings file " + dataLocation + ". " + error.message());
                    }
                    LOGGER.warn(
                        "World gen settings file is missing! Regenerating settings from server.properties: {}",
                        dataLocation
                    );
                    return worldGenSettingsFactory.get();
                    // Paper end - Regenerate missing overworld world gen settings
                }
            );
        LevelSettings settings = LevelSettings.parse(dataTag, dataConfiguration);
        WorldDimensions.Complete dimensions = worldGenSettings.dimensions().bake(datapackDimensions);
        Lifecycle lifecycle = dimensions.lifecycle().add(registryAccess.allRegistriesLifecycle());
        PrimaryLevelData worldData = PrimaryLevelData.parse(dataTag, settings, dimensions.specialWorldProperty(), lifecycle);
        return LevelDataAndDimensions.create(worldData, worldGenSettings, dimensions);
    }

    // TODO(behaviour): validateAndCreateAccess hunk (paper.disableWorldSymlinkValidation)
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
