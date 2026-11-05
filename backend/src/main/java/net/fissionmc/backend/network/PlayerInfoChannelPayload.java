package net.fissionmc.backend.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.Identifier;

/**
 * Paper: sources/net/minecraft/network/protocol/login/ClientboundCustomQueryPacket.java.patch
 * (nested ClientboundCustomQueryPacket.PlayerInfoChannelPayload; Mixin cannot add nested classes).
 */
// Paper start - MC Utils - default query payloads
public record PlayerInfoChannelPayload(Identifier id, FriendlyByteBuf buffer) implements CustomQueryPayload {
    @Override
    public void write(final FriendlyByteBuf buf) {
        buf.writeBytes(this.buffer.copy());
    }
}
// Paper end - MC Utils - default query payloads
