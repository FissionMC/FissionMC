package net.fissionmc.backend.mixin.server.dedicated;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import joptsimple.OptionSet;
import net.fissionmc.backend.boot.PaperStartup;
import net.minecraft.server.dedicated.Settings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/server/dedicated/Settings.java.patch
 *
 * <p>FissionMC: Paper threads the command line {@code OptionSet} through {@code Settings(Properties, OptionSet)},
 * {@code DedicatedServerProperties.fromFile(Path, OptionSet)} and {@code reload(..., options)}; every dedicated-server
 * {@code Settings} Paper creates receives the start-up options, so {@code getOverride} reads them from
 * {@link PaperStartup} instead of a constructor-assigned field.
 */
@Mixin(Settings.class)
public abstract class SettingsMixin {
    // CraftBukkit - SPIGOT-7465, MC-264979: Don't load if file doesn't exist
    @Inject(method = "loadFromFile", at = @At("HEAD"), cancellable = true)
    private static void paper$missingFile(final Path file, final CallbackInfoReturnable<Properties> cir) {
        if (!Files.exists(file)) cir.setReturnValue(new Properties());
    }

    // CraftBukkit - getStringRaw: return (String) this.getOverride(key, this.properties.getProperty(key));
    @Inject(method = "getStringRaw", at = @At("HEAD"), cancellable = true)
    private void paper$getOverride(final String name, final CallbackInfoReturnable<String> cir) {
        final OptionSet options = PaperStartup.options();
        if (options != null && options.has(name)) {
            cir.setReturnValue(String.valueOf(options.valueOf(name)));
        }
    }

    // TODO(behaviour): store() hunks (skip read-only files; config: Paper.skipServerPropertiesComments) and the get()
    //  "Could not load invalidly configured property" wrapper are not ported.
}
