package net.fissionmc.backend.mixin.resources;

import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/resources/Identifier.java.patch */
@Mixin(Identifier.class)
public abstract class IdentifierMixin {
    // Surface: public static final. Mixin requires non-public fields; the loader widens it.
    private static final String PAPER_NAMESPACE = "paper"; // Paper

    // TODO(behaviour): Paper validates the identifier length in the constructor ("Validate Identifier") and
    // sanitises namespace/path in assertValidNamespace/assertValidPath error messages.
}
