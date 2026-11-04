package net.fissionmc.backend.mixin.server.dedicated;

import net.minecraft.server.dedicated.DedicatedServerSettings;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/server/dedicated/DedicatedServerSettings.java.patch */
@Mixin(DedicatedServerSettings.class)
public abstract class DedicatedServerSettingsMixin {
    // CraftBukkit start
    // FissionMC: Paper adds a DedicatedServerSettings(OptionSet) constructor; Mixin cannot add constructors, so this factory
    // replaces it. Surface: public static (see surface/members/server.txt); the loader widens it.
    private static DedicatedServerSettings fromOptions(final joptsimple.OptionSet options) {
        // FissionMC: Paper loads DedicatedServerProperties.fromFile(source, options); the command line overrides are applied
        // by SettingsMixin (getOverride) for every properties object while the start-up options are set.
        return new DedicatedServerSettings(((java.io.File) options.valueOf("config")).toPath());
        // CraftBukkit end
    }
}
