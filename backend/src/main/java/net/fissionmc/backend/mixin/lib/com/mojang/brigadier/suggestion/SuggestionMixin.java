package net.fissionmc.backend.mixin.lib.com.mojang.brigadier.suggestion;

import com.mojang.brigadier.suggestion.IntegerSuggestion;
import com.mojang.brigadier.suggestion.Suggestion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/com/mojang/brigadier/suggestion/Suggestion.java.patch (+ IntegerSuggestion.java.patch) */
@Mixin(Suggestion.class)
public abstract class SuggestionMixin {
    @Shadow @Final private String text;

    // Paper start - fix unstable Suggestion comparison
    @Unique
    private static int fission$compare0(final Suggestion lhs, final Suggestion rhs, final java.util.Comparator<String> textComparator) {
        if (lhs instanceof final IntegerSuggestion lis && rhs instanceof final IntegerSuggestion ris) {
            return Integer.compare(lis.getValue(), ris.getValue());
        } else if (lhs instanceof IntegerSuggestion) {
            return -1;
        } else if (rhs instanceof IntegerSuggestion) {
            return 1;
        } else {
            return textComparator.compare(lhs.getText(), rhs.getText());
        }
    }

    @Inject(method = "compareTo(Lcom/mojang/brigadier/suggestion/Suggestion;)I", at = @At("HEAD"), cancellable = true)
    private void fission$compareTo(Suggestion o, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(fission$compare0((Suggestion) (Object) this, o, java.util.Comparator.naturalOrder())); // Paper - fix unstable Suggestion comparison
    }

    @Inject(method = "compareToIgnoreCase", at = @At("HEAD"), cancellable = true)
    private void fission$compareToIgnoreCase(Suggestion b, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(fission$compare0((Suggestion) (Object) this, b, String.CASE_INSENSITIVE_ORDER)); // Paper - fix unstable Suggestion comparison
    }
    // Paper end - fix unstable Suggestion comparison
}
