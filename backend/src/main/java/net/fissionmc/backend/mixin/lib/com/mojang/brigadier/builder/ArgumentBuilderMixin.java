package net.fissionmc.backend.mixin.lib.com.mojang.brigadier.builder;

import com.mojang.brigadier.builder.ArgumentBuilder;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Mixin;

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
    // TODO(behaviour): Paper initialises the `requirement` field with defaultRequirement() instead of `s -> true`
    // (Commands relies on identity comparison with defaultRequirement()).
}
