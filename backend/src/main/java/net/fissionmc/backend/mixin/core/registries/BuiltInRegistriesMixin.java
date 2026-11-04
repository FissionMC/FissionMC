package net.fissionmc.backend.mixin.core.registries;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/core/registries/BuiltInRegistries.java.patch */
@Mixin(BuiltInRegistries.class)
public abstract class BuiltInRegistriesMixin {
    @Shadow @Final public static Registry<? extends Registry<?>> REGISTRY;

    @Shadow private static void createContents() { throw new AssertionError(); }
    @Shadow private static void freeze() { throw new AssertionError(); }
    @Shadow private static <T extends Registry<?>> void validate(final Registry<T> registry) { throw new AssertionError(); }

    // Paper start - add built-in registry conversions
    // Surface: public static final. Mixin requires non-public fields; the loader widens it.
    private static final io.papermc.paper.registry.data.util.Conversions STATIC_ACCESS_CONVERSIONS = new io.papermc.paper.registry.data.util.Conversions(
        new net.minecraft.resources.RegistryOps.HolderLookupAdapter(net.minecraft.server.RegistryLayer.STATIC_ACCESS)
    );
    // Paper end - add built-in registry conversions

    // Paper start
    // Surface: public static. Mixin requires non-public statics; the loader widens it.
    private static void bootStrap(Runnable runnable) {
        // Paper end
        REGISTRY.freeze(); // Paper - freeze main registry early
        createContents();
        runnable.run(); // Paper
        freeze();
        validate(REGISTRY);
    }
    // TODO(behaviour): vanilla bootStrap() should delegate to bootStrap(() -> {}); internalRegister registers the API
    // registry; createContents class-loads org.bukkit.Registry and locks reference holders; freeze() runs
    // PaperRegistryListenerManager freeze listeners with STATIC_ACCESS_CONVERSIONS.
}
