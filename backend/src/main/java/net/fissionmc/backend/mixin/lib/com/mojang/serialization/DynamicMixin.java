package net.fissionmc.backend.mixin.lib.com.mojang.serialization;

import com.mojang.serialization.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/com/mojang/serialization/Dynamic.java.patch */
@Mixin(Dynamic.class)
public abstract class DynamicMixin {
    @Unique private static final boolean DEBUG_MISSING_KEYS = Boolean.getBoolean("Paper.debugDynamicMissingKeys"); // Paper - Perf: Skip toString on values like NBT

    // get(String): `DataResult.error(() -> "key missing: " + key + " in " + this.value)` is lambda$get$10
    @Inject(method = "lambda$get$10", at = @At("HEAD"), cancellable = true)
    private void fission$skipValueToString(String key, CallbackInfoReturnable<String> cir) {
        if (!DEBUG_MISSING_KEYS) cir.setReturnValue("key missing: " + key); // Paper - Perf: Skip toString on values like NBT
    }
}
