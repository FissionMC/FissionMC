package net.fissionmc.backend.mixin.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.objectweb.asm.Opcodes;
import net.minecraft.core.RegistrationInfo;

/** Paper: sources/net/minecraft/core/MappedRegistry.java.patch */
@Mixin(MappedRegistry.class)
public abstract class MappedRegistryMixin<T> {
    @Shadow private boolean frozen;
    @Shadow private @org.jspecify.annotations.Nullable Map<T, Holder.Reference<T>> unregisteredIntrusiveHolders;

    // Paper start - support pre-filling in registry mod API
    private final Map<Identifier, T> temporaryUnfrozenMap = new HashMap<>();

    public Optional<T> getValueForCopying(final ResourceKey<T> resourceKey) {
        return this.frozen ? ((Registry<T>) (Object) this).getOptional(resourceKey) : Optional.ofNullable(this.temporaryUnfrozenMap.get(resourceKey.identifier()));
    }
    // Paper end - support pre-filling in registry mod API

    @Inject(method = "register(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Lnet/minecraft/core/RegistrationInfo;)Lnet/minecraft/core/Holder$Reference;", at = @At("RETURN"))
    private void paper$fillTemporaryUnfrozenMap(ResourceKey<T> key, T value, RegistrationInfo registrationInfo, CallbackInfoReturnable<Holder.Reference<T>> cir) {
        this.temporaryUnfrozenMap.put(key.identifier(), value); // Paper - support pre-filling in registry mod API
    }

    @Inject(method = "freeze", at = @At(value = "FIELD", target = "Lnet/minecraft/core/MappedRegistry;frozen:Z", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void paper$clearTemporaryUnfrozenMap(CallbackInfoReturnable<Registry<T>> cir) {
        this.temporaryUnfrozenMap.clear(); // Paper - support pre-filling in registry mod API
    }
    // Paper - Perf: Use bigger expected size to reduce collisions (map presizing) is not ported (perf only).

    // Paper start
    // used to clear intrusive holders from GameEvent, Item, Block, EntityType, and Fluid from unused instances of those types
    public void clearIntrusiveHolder(final T instance) {
        if (this.unregisteredIntrusiveHolders != null) {
            this.unregisteredIntrusiveHolders.remove(instance);
        }
    }
    // Paper end
}
