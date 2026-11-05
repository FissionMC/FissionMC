package net.fissionmc.backend.mixin.lib.com.mojang.brigadier.builder;

import com.mojang.brigadier.builder.ArgumentBuilder;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/com/mojang/brigadier/builder/ArgumentBuilder.java.patch */
@Mixin(ArgumentBuilder.class)
public abstract class ArgumentBuilderMixin {
    // Paper start - Vanilla command permission fixes
    private static final Predicate<Object> DEFAULT_REQUIREMENT = s -> true;

    // Surface: public static. Mixin requires non-public statics; the loader widens it.
    @SuppressWarnings("unchecked")
    private static <S> Predicate<S> defaultRequirement() {
        return (Predicate<S>) DEFAULT_REQUIREMENT;
    }
    // Paper end - Vanilla command permission fixes

    @Shadow private Predicate<Object> requirement;

    // Paper - Vanilla command permission fixes: `requirement = defaultRequirement()` instead of `s -> true`
    // (Commands relies on identity comparison with defaultRequirement()).
    @Inject(method = "<init>", at = @At("RETURN"))
    private void fission$defaultRequirement(CallbackInfo ci) {
        this.requirement = defaultRequirement(); // Paper - Vanilla command permission fixes
    }
}
