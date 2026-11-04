package net.fissionmc.backend.mixin.server;

import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import java.io.File;
import net.fissionmc.backend.boot.PaperServicesHolder;
import net.fissionmc.craft.compat.PaperServices;
import net.minecraft.server.Services;
import net.minecraft.server.players.CachedUserNameToIdResolver;
import net.minecraft.server.players.ProfileResolver;
import net.minecraft.server.players.UserNameToIdResolver;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/server/Services.java.patch (PaperServices record lives in net.fissionmc.craft.compat) */
@Mixin(Services.class)
public abstract class ServicesMixin implements PaperServicesHolder {
    // Paper start - add paper configuration files
    // FissionMC: Paper adds this as a record component; Mixin cannot change the record, so it is a plain field that
    // Services.create(..., OptionSet) fills. Services built through the vanilla constructor keep null, like Paper's
    // 5-argument convenience constructor.
    private @Nullable PaperServices paper;

    public PaperServices paper() {
        return java.util.Objects.requireNonNull(this.paper);
    }

    @Override
    public void fission$setPaper(final PaperServices paper) {
        this.paper = paper;
    }

    // Surface: public static (see surface/members/server.txt).
    private static Services create(final MinecraftServicesDiscoveryService serviceAccess, final File nameCacheDir, final File userCacheFile, final joptsimple.OptionSet options) throws Exception { // Paper - add options to load paper config files; add userCacheFile parameter
        SessionService sessionService = serviceAccess.createMinecraftSessionService();
        GameProfileRepository profileRepository = serviceAccess.createProfileRepository();
        UserNameToIdResolver profileCache = new CachedUserNameToIdResolver(profileRepository, userCacheFile); // Paper
        // Paper start - load paper config files from cli options
        final java.nio.file.Path legacyConfigPath = ((File) options.valueOf("paper-settings")).toPath();
        final java.nio.file.Path configDirPath = ((File) options.valueOf("paper-settings-directory")).toPath();
        io.papermc.paper.configuration.PaperConfigurations paperConfigurations = io.papermc.paper.configuration.PaperConfigurations.setup(legacyConfigPath, configDirPath, nameCacheDir.toPath(), (File) options.valueOf("spigot-settings"));
        PaperServices paperServices = new PaperServices(
            paperConfigurations,
            new io.papermc.paper.profile.PaperFilledProfileCache()
        );
        // TODO(behaviour): Paper passes paperServices.filledProfileCache() as a third argument to ProfileResolver.Cached
        //  (ProfileResolver.java.patch); that constructor change is not ported yet.
        ProfileResolver profileResolver = new ProfileResolver.Cached(sessionService, profileCache);
        Services services = new Services(sessionService, serviceAccess.getServicesKeySet(), profileRepository, profileCache, profileResolver);
        ((PaperServicesHolder) (Object) services).fission$setPaper(paperServices); // FissionMC: Paper passes paperServices to the record constructor
        return services;
        // Paper end - load paper config files from cli options
    }
    // Paper end - add paper configuration files
}
