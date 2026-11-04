package net.fissionmc.backend.mixin.server;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import java.io.File;
import java.net.Proxy;
import java.nio.file.Path;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.fissionmc.backend.boot.PaperStartup;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Eula;
import net.minecraft.server.Main;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/server/Main.java.patch.
 *
 * <p>Paper replaces vanilla {@code main(String[])} with {@code main(OptionSet)} (CraftBukkit). FissionMC keeps vanilla's
 * method: {@code main(OptionSet)} stores the options ({@link PaperStartup}) and runs vanilla's {@code main(String[])}
 * with the vanilla arguments they imply; each Paper hunk below is an injection that is active only in that case, so
 * starting {@code net.minecraft.server.Main} directly still runs plain vanilla start-up.
 */
@Mixin(Main.class)
public abstract class MainMixin {
    private static final Logger fission$LOGGER = LogUtils.getLogger();

    // CraftBukkit - replaces main(String[] args). Surface: public static (see surface/members/server.txt).
    private static void main(final OptionSet options) {
        Main.main(PaperStartup.begin(options));
    }

    @Inject(method = "main([Ljava/lang/String;)V", at = @At("HEAD"))
    private static void paper$hookLogManager(final String[] args, final CallbackInfo ci) {
        if (PaperStartup.active()) {
            io.papermc.paper.log.LogManagerShutdownThread.hook(); // Paper - Improved watchdog support
        }
    }

    @Inject(method = "main([Ljava/lang/String;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/Bootstrap;bootStrap()V"))
    private static void paper$loadPlugins(final String[] args, final CallbackInfo ci) {
        if (PaperStartup.active()) {
            try {
                io.papermc.paper.plugin.PluginInitializerManager.load(PaperStartup.options()); // Paper
            } catch (final Exception e) {
                throw PaperStartup.sneaky(e);
            }
        }
    }

    @Redirect(method = "main([Ljava/lang/String;)V", at = @At(value = "NEW", target = "(Ljava/nio/file/Path;)Lnet/minecraft/server/dedicated/DedicatedServerSettings;"))
    private static DedicatedServerSettings paper$settingsFromOptions(final Path settingsFile) {
        if (PaperStartup.active()) {
            return DedicatedServerSettings.fromOptions(PaperStartup.options()); // CraftBukkit - CLI argument support
        }
        return new DedicatedServerSettings(settingsFile);
    }

    @Inject(
        method = "main([Ljava/lang/String;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/dedicated/DedicatedServerSettings;forceSave()V", shift = At.Shift.AFTER),
        cancellable = true
    )
    private static void paper$afterSettings(final String[] args, final CallbackInfo ci) {
        final OptionSet options = PaperStartup.options();
        if (options == null) {
            return;
        }
        // Paper start
        if (options.has("forceUpgrade") || options.has("recreateRegionFiles")) {
            fission$LOGGER.error("World upgrade and region file recreation are not yet implemented in Paper 26.1.");
            ci.cancel();
            return;
        }
        // Paper end
        // Paper start - load config files early for access below if needed
        // FissionMC: Paper runs this after RegionFileVersion.configure and the Eula construction; neither depends on it.
        try {
            PaperStartup.bukkitConfiguration = io.papermc.paper.configuration.PaperConfigurations.loadLegacyConfigFile((File) options.valueOf("bukkit-settings"));
            PaperStartup.spigotConfiguration = io.papermc.paper.configuration.PaperConfigurations.loadLegacyConfigFile((File) options.valueOf("spigot-settings"));
        } catch (final Exception e) {
            throw PaperStartup.sneaky(e);
        }
        // Paper end - load config files early for access below if needed
    }

    // vanilla: if (options.has(initSettings)) { LOGGER.info("Initialized '{}' and '{}'", ...); return; }
    @Inject(
        method = "main([Ljava/lang/String;)V",
        at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", ordinal = 0)
    )
    private static void paper$initSettings(final String[] args, final CallbackInfo ci) {
        final OptionSet options = PaperStartup.options();
        if (options == null) {
            return;
        }
        try {
            // CraftBukkit start - SPIGOT-5761: Create bukkit.yml and commands.yml if not present
            File configFile = (File) options.valueOf("bukkit-settings");
            org.bukkit.configuration.file.YamlConfiguration configuration = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
            configuration.options().copyDefaults(true);
            configuration.setDefaults(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Main.class.getClassLoader().getResourceAsStream("configurations/bukkit.yml"), java.nio.charset.StandardCharsets.UTF_8)));
            configuration.save(configFile);

            File commandFile = (File) options.valueOf("commands-settings");
            org.bukkit.configuration.file.YamlConfiguration commandsConfiguration = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(commandFile);
            commandsConfiguration.options().copyDefaults(true);
            commandsConfiguration.setDefaults(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Main.class.getClassLoader().getResourceAsStream("configurations/commands.yml"), java.nio.charset.StandardCharsets.UTF_8)));
            commandsConfiguration.save(commandFile);
            // CraftBukkit end
        } catch (final java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @WrapOperation(method = "main([Ljava/lang/String;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/Eula;hasAgreedToEULA()Z"))
    private static boolean paper$eulaProperty(final Eula eula, final Operation<Boolean> original) {
        // Paper start - eula system property
        if (PaperStartup.active() && Boolean.getBoolean("com.mojang.eula.agree")) {
            fission$LOGGER.warn("You have used the Paper command line EULA agreement flag.");
            fission$LOGGER.warn("By using this setting you are indicating your agreement to Mojang's EULA (https://aka.ms/MinecraftEULA).");
            fission$LOGGER.warn("If you do not agree to the above EULA please stop your server and remove this flag immediately.");
            return true;
        }
        // Paper end - eula system property
        return original.call(eula);
    }

    @Inject(
        method = "main([Ljava/lang/String;)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/authlib/services/MinecraftServicesDiscoveryService;create(Ljava/net/Proxy;)Lcom/mojang/authlib/services/MinecraftServicesDiscoveryService;"),
        cancellable = true
    )
    private static void paper$beforeServices(final String[] args, final CallbackInfo ci) {
        if (!PaperStartup.active()) {
            return;
        }
        // Paper start - Detect headless JRE
        String awtException = io.papermc.paper.util.ServerEnvironment.awtDependencyCheck();
        if (awtException != null) {
            fission$LOGGER.error("You are using a headless JRE distribution.");
            fission$LOGGER.error("This distribution is missing certain graphic libraries that the Minecraft server needs to function.");
            fission$LOGGER.error("For instructions on how to install the non-headless JRE, see https://docs.papermc.io/misc/java-install");
            fission$LOGGER.error("");
            fission$LOGGER.error(awtException);
            ci.cancel();
            return;
        }
        // Paper end - Detect headless JRE

        org.spigotmc.SpigotConfig.disabledAdvancements = PaperStartup.spigotConfiguration.getStringList("advancements.disabled"); // Paper - fix SPIGOT-5885, must be set early in init
    }

    @WrapOperation(
        method = "main([Ljava/lang/String;)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/authlib/services/MinecraftServicesDiscoveryService;create(Ljava/net/Proxy;)Lcom/mojang/authlib/services/MinecraftServicesDiscoveryService;")
    )
    private static MinecraftServicesDiscoveryService paper$servicesDiscovery(final Proxy proxy, final Operation<MinecraftServicesDiscoveryService> original) {
        if (PaperStartup.active()) {
            return com.destroystokyo.paper.profile.PaperServicesDiscoveryService.create(Proxy.NO_PROXY); // Paper - override authentication service
        }
        return original.call(proxy);
    }

    @WrapOperation(
        method = "main([Ljava/lang/String;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/Services;create(Lcom/mojang/authlib/services/MinecraftServicesDiscoveryService;Ljava/io/File;)Lnet/minecraft/server/Services;")
    )
    private static Services paper$services(final MinecraftServicesDiscoveryService discovery, final File universePath, final Operation<Services> original) {
        final OptionSet options = PaperStartup.options();
        if (options == null) {
            return original.call(discovery, universePath);
        }
        // Paper start - fix SPIGOT-5824 (the universe itself is passed to vanilla as --universe, see PaperStartup.begin)
        File userCacheFile = new File(Services.USERID_CACHE_FILE);
        if (options.has("universe")) {
            userCacheFile = new File(universePath, Services.USERID_CACHE_FILE);
        }
        // Paper end - fix SPIGOT-5824
        try {
            return Services.create(discovery, universePath, userCacheFile, options); // Paper - pass OptionSet to load paper config files; override authentication service; fix world-container
        } catch (final Exception e) {
            throw PaperStartup.sneaky(e);
        }
    }

    @WrapOperation(
        method = "main([Ljava/lang/String;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/repository/ServerPacksSource;createPackRepository(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;)Lnet/minecraft/server/packs/repository/PackRepository;")
    )
    private static PackRepository paper$packRepository(final LevelStorageSource.LevelStorageAccess access, final Operation<PackRepository> original) {
        final OptionSet options = PaperStartup.options();
        if (options == null) {
            return original.call(access);
        }
        io.papermc.paper.world.migration.WorldFolderMigration.didInitialLoad = true; // Paper
        final PackRepository packRepository = original.call(access);
        // Paper start
        if (!options.has("safeMode")) {
            try {
                io.papermc.paper.datapack.DynamicBuiltinPacks.refreshAllMetadata(access);
            } catch (final java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        // Paper end
        return packRepository;
    }

    // WorldLoader.load(..., context -> { ... }) lambda: (levelDataTag, access, settings, options, demo, bonusChest, context)
    @Inject(method = "lambda$main$1", at = @At("HEAD"))
    private static void paper$captureWorldLoader(
        final Dynamic<?> levelDataTag, final LevelStorageSource.LevelStorageAccess access, final DedicatedServerSettings settings,
        final OptionSet vanillaOptions, final OptionSpec<?> demo, final OptionSpec<?> bonusChest, final WorldLoader.DataLoadContext context,
        final CallbackInfoReturnable<WorldLoader.DataLoadOutput<?>> cir
    ) {
        if (PaperStartup.active()) {
            PaperStartup.WORLD_LOADER.set(context); // CraftBukkit
            PaperStartup.settings = settings; // FissionMC: for paper$levelData below
        }
    }

    @WrapOperation(
        method = "lambda$main$1",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelStorageSource;getLevelDataAndDimensions(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lcom/mojang/serialization/Dynamic;Lnet/minecraft/world/level/WorldDataConfiguration;Lnet/minecraft/core/Registry;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/level/storage/LevelDataAndDimensions;")
    )
    private static LevelDataAndDimensions paper$levelData(
        final LevelStorageSource.LevelStorageAccess access, final Dynamic<?> levelDataTag, final WorldDataConfiguration dataConfiguration,
        final Registry<LevelStem> datapackDimensions, final HolderLookup.Provider registryAccess, final Operation<LevelDataAndDimensions> original
    ) {
        if (!PaperStartup.active()) {
            return original.call(access, levelDataTag, dataConfiguration, datapackDimensions, registryAccess);
        }
        // FissionMC: the lambda's settings and context arguments, stashed at its HEAD (paper$captureWorldLoader)
        final DedicatedServerSettings settings = PaperStartup.settings;
        final WorldLoader.DataLoadContext context = PaperStartup.WORLD_LOADER.get();
        // Paper start - migrate startup world
        try {
            io.papermc.paper.world.migration.WorldFolderMigration.migrateStartupWorld(
                access,
                context.datapackWorldRegistries(),
                access.getLevelId(), // FissionMC: Paper's levelName local; access was created for it
                LevelStem.OVERWORLD,
                Registries.levelStemToLevel(LevelStem.OVERWORLD)
            );
        } catch (final java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Failed to migrate world storage for " + LevelStem.OVERWORLD.identifier(), ex);
        }
        // Paper end - migrate startup world
        return LevelStorageSource.getLevelDataAndDimensions(
            access, levelDataTag, dataConfiguration, datapackDimensions, registryAccess
            // Paper start - pass dimension and regenerate missing world gen settings
            , net.minecraft.world.level.Level.OVERWORLD
            , () -> {
                final DedicatedServerProperties properties = settings.getProperties();
                return new WorldGenSettings(
                    properties.worldOptions, properties.createDimensions(context.datapackWorldRegistries())
                );
            }
            // Paper end - pass dimension and regenerate missing world gen settings
        );
    }

    // Paper comments out the forceUpgrade/recreateRegionFiles block (guarded above) and access.saveDataTag(data)
    @WrapWithCondition(
        method = "main([Ljava/lang/String;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;saveDataTag(Lnet/minecraft/world/level/storage/WorldData;)V")
    )
    private static boolean paper$skipSaveDataTag(final LevelStorageSource.LevelStorageAccess access, final WorldData data) {
        return !PaperStartup.active();
    }

    // MinecraftServer.spin(thread -> { new DedicatedServer(...); ... }) lambda
    @WrapWithCondition(method = "lambda$main$3", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/dedicated/DedicatedServer;setId(Ljava/lang/String;)V"))
    private static boolean paper$setIdOnlyIfGiven(final DedicatedServer server, final String id) {
        return !PaperStartup.active() || id != null; // Paper - only if options.has("serverId")
    }

    // CraftBukkit - Paper drops vanilla's "Server Shutdown Thread"; MinecraftServer's constructor registers ServerShutdownThread
    @WrapWithCondition(method = "main([Ljava/lang/String;)V", at = @At(value = "INVOKE", target = "Ljava/lang/Runtime;addShutdownHook(Ljava/lang/Thread;)V"))
    private static boolean paper$noVanillaShutdownThread(final Runtime runtime, final Thread hook) {
        return !PaperStartup.active();
    }
}
