package net.fissionmc.backend.mixin.server.players;

import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/server/players/UserNameToIdResolver.java.patch
 * Paper declares these abstract; Mixin adds them as defaults so implementations without them link
 * (CachedUserNameToIdResolverMixin provides the real ones).
 */
@Mixin(UserNameToIdResolver.class)
public interface UserNameToIdResolverMixin {
    default void save(boolean async) { // Paper
        throw new UnsupportedOperationException("FissionMC: abstract in Paper");
    }

    default @org.jspecify.annotations.Nullable NameAndId getIfCached(String name) { // Paper
        throw new UnsupportedOperationException("FissionMC: abstract in Paper");
    }
}
