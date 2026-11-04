package net.fissionmc.backend.mixin.resources;

import net.minecraft.resources.ResourceManagerRegistryLoadTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/resources/ResourceManagerRegistryLoadTask.java.patch */
@Mixin(ResourceManagerRegistryLoadTask.class)
public abstract class ResourceManagerRegistryLoadTaskMixin<T> extends net.minecraft.resources.RegistryLoadTask<T> {
    private ResourceManagerRegistryLoadTaskMixin() {
        super(null, null, null);
    }

    // load(): thenAcceptAsync(loadedEntries -> { this.registerElements(...); [Paper]; ... })
    @Inject(method = "lambda$load$3", at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceManagerRegistryLoadTask;registerElements(Ljava/util/stream/Stream;)V", shift = At.Shift.AFTER))
    private void paper$lockReferenceHolders(java.util.Map<?, ?> loadedEntries, CallbackInfo ci) {
        io.papermc.paper.registry.PaperRegistryAccess.instance().lockReferenceHolders(this.registryKey()); // Paper - lock reference holders
        // TODO(behaviour): Paper - create conversions / run pre-freeze listeners (PaperRegistryListenerManager.runFreezeListeners),
        //  registerElements with listeners, TagLoader.loadTagsForRegistry(..., registry, Cause.INITIAL) (tag lifecycle)
    }
}
