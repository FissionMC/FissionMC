package net.fissionmc.backend.mixin.core;

import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/core/HolderLookup.java.patch */
@Mixin(HolderLookup.RegistryLookup.class)
public interface HolderLookup_RegistryLookupMixin<T> {
    // Paper declares this abstract (surface: abstract). At run time it is a default that throws, so implementers
    // without a mixin fail loudly; all vanilla implementers get one (MappedRegistry, BootstrapRegistry,
    // PatchedRegistry, RegistryLookup.Delegate and the filterElements anonymous class).
    default Optional<T> getValueForCopying(ResourceKey<T> resourceKey) { // Paper - add method to get the value for pre-filling builders in the reg mod API
        throw new UnsupportedOperationException("getValueForCopying not implemented by " + this.getClass().getName());
    }
}
