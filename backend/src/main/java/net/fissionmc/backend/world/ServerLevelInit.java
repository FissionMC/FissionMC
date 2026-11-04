package net.fissionmc.backend.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.jspecify.annotations.Nullable;

/**
 * FissionMC: carries the arguments Paper adds to the ServerLevel (and Level) constructors from
 * {@code ServerLevel.create(...)} into the constructor injections (Mixin cannot add constructor parameters).
 */
public record ServerLevelInit(
    WorldGenSettings worldGenSettings,
    ResourceKey<LevelStem> typeKey,
    org.bukkit.World.Environment env,
    org.bukkit.generator.@Nullable ChunkGenerator gen,
    org.bukkit.generator.@Nullable BiomeProvider biomeProvider,
    SavedDataStorage savedDataStorage,
    io.papermc.paper.world.PaperWorldLoader.LoadedWorldData loadedWorldData,
    GameRules gameRules
) {
    private static final ThreadLocal<@Nullable ServerLevelInit> PENDING = new ThreadLocal<>();

    public static void push(final ServerLevelInit init) {
        PENDING.set(init);
    }

    public static @Nullable ServerLevelInit peek() {
        return PENDING.get();
    }

    public static void clear() {
        PENDING.remove();
    }
}
