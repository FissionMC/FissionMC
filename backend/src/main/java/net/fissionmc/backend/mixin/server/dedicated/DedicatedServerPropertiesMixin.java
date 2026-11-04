package net.fissionmc.backend.mixin.server.dedicated;

import java.util.Properties;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/server/dedicated/DedicatedServerProperties.java.patch */
@Mixin(DedicatedServerProperties.class)
public abstract class DedicatedServerPropertiesMixin extends net.minecraft.server.dedicated.Settings<DedicatedServerProperties> {
    @Shadow protected abstract DedicatedServerProperties reload(RegistryAccess registryAccess, Properties properties);

    private DedicatedServerPropertiesMixin(Properties properties) {
        super(properties);
    }

    private final boolean debug = this.get("debug", false); // CraftBukkit

    // CraftBukkit start
    public DedicatedServerProperties reload(final RegistryAccess registryAccess, final Properties properties, final joptsimple.OptionSet options) {
        // TODO(behaviour): Paper's Settings/DedicatedServerProperties take the OptionSet (command line overrides); options are not threaded through yet
        return this.reload(registryAccess, properties);
        // CraftBukkit end
    }
}
