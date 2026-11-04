package net.fissionmc.backend.mixin.core.registries;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BootstrapRegistry;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/core/registries/BootstrapRegistry.java.patch */
@Mixin(BootstrapRegistry.class)
public abstract class BootstrapRegistryMixin<T> {
    @Shadow public abstract Optional<Holder.Reference<T>> get(ResourceKey<T> key);

    // Paper start - add getValueForCopying method
    public Optional<T> getValueForCopying(final ResourceKey<T> resourceKey) {
        return this.get(resourceKey).map(Holder.Reference::value);
    }
    // Paper end - add getValueForCopying method
}
