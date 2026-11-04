package net.fissionmc.backend.mixin.core;

import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/core/HolderLookup.java.patch (anonymous Delegate in RegistryLookup#filterElements) */
@Mixin(targets = "net.minecraft.core.HolderLookup$RegistryLookup$1")
public abstract class HolderLookup_RegistryLookup_1Mixin<T> {
    @Shadow(remap = false) @Final Predicate<T> val$filter;

    @Shadow public abstract HolderLookup.RegistryLookup<T> parent();

    // Paper start - add getValueForCopying
    public Optional<T> getValueForCopying(final ResourceKey<T> resourceKey) {
        return this.parent().getValueForCopying(resourceKey).filter(this.val$filter);
    }
    // Paper end - add getValueForCopying
}
