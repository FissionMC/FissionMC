package net.fissionmc.backend.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;

/**
 * Paper: sources/net/minecraft/network/protocol/login/ServerboundCustomQueryAnswerPacket.java.patch
 * (nested ServerboundCustomQueryAnswerPacket.QueryAnswerPayload; Mixin cannot add nested classes).
 */
// Paper start - MC Utils - default query payloads
public final class QueryAnswerPayload implements CustomQueryAnswerPayload {
    public final FriendlyByteBuf buffer;

    public QueryAnswerPayload(final net.minecraft.network.FriendlyByteBuf buffer) {
        this.buffer = buffer;
    }

    @Override
    public void write(final net.minecraft.network.FriendlyByteBuf buf) {
        buf.writeBytes(this.buffer.copy());
    }
}
// Paper end - MC Utils - default query payloads
