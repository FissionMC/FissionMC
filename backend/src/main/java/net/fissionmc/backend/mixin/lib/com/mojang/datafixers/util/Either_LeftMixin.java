package net.fissionmc.backend.mixin.lib.com.mojang.datafixers.util;

import java.util.Optional;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/com/mojang/datafixers/util/Either.java.patch (Perf: Reduce Either Optional allocation) */
@Mixin(targets = "com.mojang.datafixers.util.Either$Left")
public abstract class Either_LeftMixin<L> {
    @Shadow @Final private L value;
    @Unique private Optional<L> fission$valueOptional; // Paper - Perf: Reduce Either Optional allocation

    @Inject(method = "left", at = @At("HEAD"), cancellable = true)
    private void fission$cachedLeft(CallbackInfoReturnable<Optional<L>> cir) {
        cir.setReturnValue(this.fission$valueOptional == null ? this.fission$valueOptional = Optional.of(this.value) : this.fission$valueOptional); // Paper - Perf: Reduce Either Optional allocation
    }
}
