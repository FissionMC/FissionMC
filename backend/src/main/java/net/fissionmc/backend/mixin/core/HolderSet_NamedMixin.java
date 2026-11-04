package net.fissionmc.backend.mixin.core;

import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/core/HolderSet.java.patch */
@Mixin(HolderSet.Named.class)
public abstract class HolderSet_NamedMixin<T> {
    private @Nullable Set<io.papermc.paper.registry.TypedKey<?>> typedKeys; // Paper - cache typed key set for constant contains calls

    @Shadow protected abstract List<Holder<T>> contents();

    @Inject(method = "bind", at = @At("TAIL"))
    private void fission$resetTypedKeys(final List<Holder<T>> contents, final CallbackInfo ci) {
        this.typedKeys = null; // Paper - reset if tag is re-bound
    }

    // Paper start - cache typed key set for constant contains calls
    public boolean contains(io.papermc.paper.registry.TypedKey<?> key) {
        if (this.typedKeys == null) {
            this.typedKeys = this.contents().stream().map(h -> io.papermc.paper.registry.PaperRegistries.fromNms(h.unwrapKey().orElseThrow())).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        return this.typedKeys.contains(key);
    }
    // Paper end - cache typed key set for constant contains calls
}
