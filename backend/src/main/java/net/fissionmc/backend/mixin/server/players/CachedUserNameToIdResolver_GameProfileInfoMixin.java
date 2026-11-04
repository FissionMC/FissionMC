package net.fissionmc.backend.mixin.server.players;

import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Accessor for the package-private CachedUserNameToIdResolver.GameProfileInfo (used by CachedUserNameToIdResolverMixin). */
@Mixin(targets = "net.minecraft.server.players.CachedUserNameToIdResolver$GameProfileInfo")
public interface CachedUserNameToIdResolver_GameProfileInfoMixin {
    @Invoker("nameAndId")
    NameAndId fission$nameAndId();

    @Invoker("setLastAccess")
    void fission$setLastAccess(long lastAccess);
}
