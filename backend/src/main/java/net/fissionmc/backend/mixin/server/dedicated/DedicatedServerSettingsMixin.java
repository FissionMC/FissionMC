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
        // TODO(behaviour): Paper loads DedicatedServerProperties.fromFile(source, options) so command line options override properties.
        return new DedicatedServerSettings(((java.io.File) options.valueOf("config")).toPath());
        // CraftBukkit end
    }
}
