package net.fissionmc.backend.mixin.core;

import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/core/HolderLookup.java.patch */
@Mixin(HolderLookup.RegistryLookup.Delegate.class)
public interface HolderLookup_RegistryLookup_DelegateMixin<T> {
    @Shadow HolderLookup.RegistryLookup<T> parent();

    // Paper start - add getValueForCopying
    default Optional<T> getValueForCopying(ResourceKey<T> resourceKey) {
        return this.parent().getValueForCopying(resourceKey);
    }
    // Paper end - add getValueForCopying
}
