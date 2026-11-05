package net.fissionmc.backend.registry;

import net.minecraft.core.HolderLookup;

/** Implemented by RegistryDataLoader's anonymous RegistryInfoLookup (RegistryDataLoader_3Mixin). */
public interface ProviderForBuildersHolder {
    void fission$setProviderForBuilders(HolderLookup.Provider providerForBuilders);
}
