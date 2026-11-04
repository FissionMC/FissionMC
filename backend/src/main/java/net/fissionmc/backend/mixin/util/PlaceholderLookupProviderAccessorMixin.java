package net.fissionmc.backend.mixin.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.util.PlaceholderLookupProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to {@code PlaceholderLookupProvider.context} for {@link PlaceholderLookupProvider_1Mixin}. */
@Mixin(PlaceholderLookupProvider.class)
public interface PlaceholderLookupProviderAccessorMixin {
    @Accessor("context")
    HolderLookup.Provider fission$context();
}
