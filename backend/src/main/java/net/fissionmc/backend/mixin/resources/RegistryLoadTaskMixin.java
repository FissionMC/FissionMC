package net.fissionmc.backend.mixin.resources;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.papermc.paper.registry.data.util.Conversions;
import net.fissionmc.backend.registry.FissionRegistryLoadTask;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.RegistryLoadTask;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/resources/RegistryLoadTask.java.patch */
@Mixin(RegistryLoadTask.class)
public abstract class RegistryLoadTaskMixin<T> implements FissionRegistryLoadTask<T> {
    @Shadow @Final private WritableRegistry<T> registry;

    // Paper - pass conversions: Paper adds a Conversions parameter to registerElements; subclasses set it here before
    // calling the vanilla registerElements(Stream) (null for NetworkRegistryLoadTask, as in Paper).
    @Unique private @Nullable Conversions fission$conversions;

    @Override
    public WritableRegistry<T> fission$registry() {
        return this.registry; // Paper - package-private
    }

    @Override
    public @Nullable Conversions fission$conversions() {
        return this.fission$conversions;
    }

    @Override
    public void fission$setConversions(@Nullable Conversions conversions) {
        this.fission$conversions = conversions;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void paper$registerApiRegistry(CallbackInfo ci) {
        io.papermc.paper.registry.PaperRegistryAccess.instance().registerRegistry(this.registry); // Paper - initialize API registry
    }

    // registerElements: .ifLeft(value -> this.registry.register(element.key, value, element.registrationInfo))
    @WrapOperation(method = "lambda$registerElements$1", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/WritableRegistry;register(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Lnet/minecraft/core/RegistrationInfo;)Lnet/minecraft/core/Holder$Reference;"))
    @SuppressWarnings("unchecked")
    private Holder.Reference<T> paper$registerWithListeners(WritableRegistry<T> registry, ResourceKey<T> key, Object value, RegistrationInfo registrationInfo, Operation<Holder.Reference<T>> original) {
        // FissionMC: PaperRegistryListenerManager requires the bootstrap entrypoint (Paper enters it in
        // Bootstrap.bootStrap); fall back to the plain registration until that hunk is ported.
        if (!io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler.INSTANCE.hasEntered(io.papermc.paper.plugin.entrypoint.Entrypoint.BOOTSTRAPPER)) {
            return original.call(registry, key, value, registrationInfo);
        }
        io.papermc.paper.registry.PaperRegistryListenerManager.INSTANCE.registerWithListeners(registry, key, (T) value, registrationInfo, this.fission$conversions); // Paper - register with listeners
        return null; // the lambda discards the result
    }
}
