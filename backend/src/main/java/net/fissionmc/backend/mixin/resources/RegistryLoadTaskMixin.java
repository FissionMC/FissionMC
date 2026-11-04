package net.fissionmc.backend.mixin.resources;

import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.RegistryLoadTask;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/resources/RegistryLoadTask.java.patch */
@Mixin(RegistryLoadTask.class)
public abstract class RegistryLoadTaskMixin<T> {
    @Shadow @Final private WritableRegistry<T> registry;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void paper$registerApiRegistry(CallbackInfo ci) {
        io.papermc.paper.registry.PaperRegistryAccess.instance().registerRegistry(this.registry); // Paper - initialize API registry
    }

    // TODO(behaviour): registerElements(stream, Conversions) -> PaperRegistryListenerManager.registerWithListeners (registry
    //  modification API for plugin bootstrappers); registry field package-private
}
