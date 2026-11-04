package net.fissionmc.backend.mixin.server.network;

import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/server/network/ServerLoginPacketListenerImpl.java.patch
 * requestedUsername and transferred are widened to public via surface/members/player.txt.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
    // Surface: public. Mixin requires private fields; the loader widens them.
    private boolean iKnowThisMayNotBeTheBestIdeaButPleaseDisableUsernameValidation = false; // Paper - username validation overriding // TODO(behaviour): read in handleHello
    private java.util.@Nullable UUID requestedUuid; // Paper

    @Inject(method = "handleHello", at = @At("HEAD"))
    private void fission$handleHello(final ServerboundHelloPacket packet, final CallbackInfo ci) {
        this.requestedUuid = packet.profileId(); // Paper
    }

    // TODO(behaviour): Paper login flow (async pre-login events, velocity forwarding, PaperPlayerLoginConnection, disconnect overloads)
}
