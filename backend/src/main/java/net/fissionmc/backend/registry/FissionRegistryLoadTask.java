package net.fissionmc.backend.registry;

import io.papermc.paper.registry.data.util.Conversions;
import net.minecraft.core.WritableRegistry;
import org.jspecify.annotations.Nullable;

/**
 * Implemented by {@code RegistryLoadTask} (RegistryLoadTaskMixin). Paper passes {@link Conversions} into
 * {@code registerElements(Stream, Conversions)} and makes {@code registry} package-private; subclass mixins reach both
 * through this interface.
 */
public interface FissionRegistryLoadTask<T> {
    WritableRegistry<T> fission$registry();

    @Nullable Conversions fission$conversions();

    void fission$setConversions(@Nullable Conversions conversions);
}
