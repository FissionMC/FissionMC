package net.fissionmc.backend.mixin.core.registries;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
    // Paper start - bootStrap() delegates to bootStrap(() -> {})
    @Inject(method = "bootStrap()V", at = @At("HEAD"), cancellable = true)
    private static void paper$bootStrap(CallbackInfo ci) {
        bootStrap(() -> {});
        ci.cancel();
    }
    // Paper end

    // FissionMC: Paper registers right after Bootstrap.checkBootstrapCalled; the argument (typed with the private
    // RegistryBootstrap) cannot be captured there, so this runs at HEAD on the registry argument.
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "internalRegister", at = @At("HEAD"), argsOnly = true)
    private static net.minecraft.core.WritableRegistry<?> paper$registerApiRegistry(final net.minecraft.core.WritableRegistry<?> registry) {
        io.papermc.paper.registry.PaperRegistryAccess.instance().registerRegistry(registry); // Paper - initialize API registry
        return registry;
    }

    @Inject(method = "createContents", at = @At("HEAD"))
    private static void paper$classLoadBukkitRegistry(CallbackInfo ci) {
        // Paper start - class-load org.bukkit.Registry
        // we have to class-load Registry here to create all the CraftRegistry instances
        // that will be created when Registry is class-loaded before RegistryAccess#getRegistry
        // would try to create them in lockReferenceHolder
        try {
            Class.forName(org.bukkit.Registry.class.getName());
        } catch (final ClassNotFoundException ex) {
            throw new RuntimeException(ex);
        }
        // Paper end - class-load org.bukkit.Registry
    }

    // LOADERS.forEach((key, value) -> { ...; PaperRegistryAccess.instance().lockReferenceHolders(...) })
    @Inject(method = "lambda$createContents$0", at = @At("TAIL"))
    private static void paper$lockReferenceHolders(final net.minecraft.resources.Identifier key, final java.util.function.Supplier<?> value, CallbackInfo ci) {
        io.papermc.paper.registry.PaperRegistryAccess.instance().lockReferenceHolders(net.minecraft.resources.ResourceKey.createRegistryKey(key)); // Paper - lock reference holder creation
    }

    // freeze(): for each registry: bindBootstrappedTagsToEmpty(registry); [Paper: runFreezeListeners]; registry.freeze();
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "freeze", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/registries/BuiltInRegistries;bindBootstrappedTagsToEmpty(Lnet/minecraft/core/Registry;)V"))
    private static void paper$runFreezeListeners(final Registry<?> registry, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        original.call(registry);
        io.papermc.paper.registry.PaperRegistryListenerManager.INSTANCE.runFreezeListeners(registry.key(), STATIC_ACCESS_CONVERSIONS); // Paper
    }
}
