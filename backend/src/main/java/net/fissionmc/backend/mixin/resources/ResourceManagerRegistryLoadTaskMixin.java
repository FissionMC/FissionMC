package net.fissionmc.backend.mixin.resources;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.fissionmc.backend.registry.FissionRegistryLoadTask;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceManagerRegistryLoadTask;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/resources/ResourceManagerRegistryLoadTask.java.patch */
@Mixin(ResourceManagerRegistryLoadTask.class)
public abstract class ResourceManagerRegistryLoadTaskMixin<T> extends net.minecraft.resources.RegistryLoadTask<T> {
    private ResourceManagerRegistryLoadTaskMixin() {
        super(null, null, null);
    }

    @Inject(method = "load", at = @At("HEAD"))
    @SuppressWarnings("unchecked")
    private void paper$createConversions(RegistryOps.RegistryInfoLookup context, Executor executor, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        final io.papermc.paper.registry.data.util.Conversions conversions = new io.papermc.paper.registry.data.util.Conversions(context); // Paper - create conversions
        ((FissionRegistryLoadTask<T>) (Object) this).fission$setConversions(conversions); // consumed by registerElements (RegistryLoadTaskMixin)
    }

    // load(): thenAcceptAsync(loadedEntries -> { this.registerElements(..., conversions); [Paper]; ... })
    @Inject(method = "lambda$load$3", at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceManagerRegistryLoadTask;registerElements(Ljava/util/stream/Stream;)V", shift = At.Shift.AFTER))
    @SuppressWarnings("unchecked")
    private void paper$lockReferenceHolders(Map<?, ?> loadedEntries, CallbackInfo ci) {
        io.papermc.paper.registry.PaperRegistryAccess.instance().lockReferenceHolders(this.registryKey()); // Paper - lock reference holders
        io.papermc.paper.registry.PaperRegistryListenerManager.INSTANCE.runFreezeListeners(this.registryKey(), ((FissionRegistryLoadTask<T>) (Object) this).fission$conversions()); // Paper - run pre-freeze listeners
    }

    // Paper - tag lifecycle - add registry, cause
    @WrapOperation(method = "lambda$load$3", at = @At(value = "INVOKE", target = "Lnet/minecraft/tags/TagLoader;loadTagsForRegistry(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/tags/TagLoader$ElementLookup;)Ljava/util/Map;"))
    @SuppressWarnings("unchecked")
    private Map<TagKey<T>, List<Holder<T>>> paper$tagLifecycle(ResourceManager manager, ResourceKey<? extends Registry<T>> registryKey, TagLoader.ElementLookup<Holder<T>> lookup, Operation<Map<TagKey<T>, List<Holder<T>>>> original) {
        return TagLoader.loadTagsForRegistry(manager, registryKey, lookup, ((FissionRegistryLoadTask<T>) (Object) this).fission$registry(), io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause.INITIAL); // Paper - tag lifecycle - add registry, cause
    }
}
