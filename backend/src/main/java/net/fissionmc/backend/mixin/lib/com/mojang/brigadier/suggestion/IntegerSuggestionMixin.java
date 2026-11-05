package net.fissionmc.backend.mixin.lib.com.mojang.brigadier.suggestion;

import com.mojang.brigadier.suggestion.IntegerSuggestion;
import com.mojang.brigadier.suggestion.Suggestion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/com/mojang/brigadier/suggestion/IntegerSuggestion.java.patch */
@Mixin(IntegerSuggestion.class)
public abstract class IntegerSuggestionMixin extends Suggestion {
    private IntegerSuggestionMixin() {
        super(null, null);
    }

    // Paper start - fix unstable Suggestion comparison: `if (false && o instanceof IntegerSuggestion)` -> always super
    @Inject(method = "compareTo(Lcom/mojang/brigadier/suggestion/Suggestion;)I", at = @At("HEAD"), cancellable = true)
    private void fission$compareTo(Suggestion o, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(super.compareTo(o));
    }

    @Inject(method = "compareToIgnoreCase", at = @At("HEAD"), cancellable = true)
    private void fission$compareToIgnoreCase(Suggestion b, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(super.compareToIgnoreCase(b));
    }
    // Paper end - fix unstable Suggestion comparison
}
