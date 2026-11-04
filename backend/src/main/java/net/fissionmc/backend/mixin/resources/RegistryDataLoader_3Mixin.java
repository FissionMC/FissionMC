package net.fissionmc.backend.mixin.resources;

import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/resources/RegistryDataLoader.java.patch (anonymous RegistryInfoLookup in createContext) */
@Mixin(targets = "net.minecraft.resources.RegistryDataLoader$3")
public abstract class RegistryDataLoader_3Mixin {
    // Paper captures a local `providerForBuilders` here; Mixin cannot add captured locals, so it is a field.
    private HolderLookup.Provider providerForBuilders;

    // Paper start - add method to get the value for pre-filling builders in the reg mod API
    public HolderLookup.Provider lookupForValueCopyViaBuilders() {
        return this.providerForBuilders;
    }
    // Paper end - add method to get the value for pre-filling builders in the reg mod API
    // TODO(behaviour): RegistryDataLoader#createContext must set providerForBuilders to
    // HolderLookup.Provider.create(Stream.concat(contextRegistries.stream(), newRegistriesAndLoaders.stream().map(t -> t.registry)))
    // on the returned instance (inject at RETURN + accessor); until then this returns null.
}
