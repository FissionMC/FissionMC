package net.fissionmc.backend.mixin.resources;

import java.util.List;
import net.fissionmc.backend.registry.FissionRegistryLoadTask;
import net.fissionmc.backend.registry.ProviderForBuildersHolder;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryLoadTask;
import net.minecraft.resources.RegistryOps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/resources/RegistryDataLoader.java.patch */
@Mixin(RegistryDataLoader.class)
public abstract class RegistryDataLoaderMixin {

    @Inject(method = "createContext", at = @At("RETURN"))
    private static void paper$providerForBuilders(List<HolderLookup.RegistryLookup<?>> contextRegistries, List<RegistryLoadTask<?>> newRegistriesAndLoaders, CallbackInfoReturnable<RegistryOps.RegistryInfoLookup> cir) {
        // this creates a HolderLookup.Provider to be used exclusively to obtain real instances of values to pre-populate builders
        // for the Registry Modification API. This concatenates the context registry lookups and the empty registries about to be filled.
        final HolderLookup.Provider providerForBuilders = HolderLookup.Provider.create(java.util.stream.Stream.concat(contextRegistries.stream(), newRegistriesAndLoaders.stream().map(t -> ((FissionRegistryLoadTask<?>) t).fission$registry()))); // Paper - add method to get the value for pre-filling builders in the reg mod API
        ((ProviderForBuildersHolder) cir.getReturnValue()).fission$setProviderForBuilders(providerForBuilders);
    }
}
