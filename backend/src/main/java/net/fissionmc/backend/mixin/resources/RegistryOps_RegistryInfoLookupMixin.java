package net.fissionmc.backend.mixin.resources;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/resources/RegistryOps.java.patch */
@Mixin(RegistryOps.RegistryInfoLookup.class)
public interface RegistryOps_RegistryInfoLookupMixin {
    // Paper declares this abstract (surface: abstract). At run time it is a default that throws; the vanilla
    // implementers (HolderLookupAdapter, RegistryDataLoader$3, PlaceholderLookupProvider$1) override it.
    default HolderLookup.Provider lookupForValueCopyViaBuilders() { // Paper - add method to get the value for pre-filling builders in the reg mod API
        throw new UnsupportedOperationException("lookupForValueCopyViaBuilders not implemented by " + this.getClass().getName());
    }
}
