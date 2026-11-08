package net.fissionmc.backend.relay;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Java because Kotlin cannot see the nested {@code SynchedEntityData.DataValue} class through the surface jar. */
public final class GhostPackets {
    private GhostPackets() {}

    /** Payload of vanilla's SetEntityData packet for [p] (non-default values) after the entity id varint. */
    public static byte[] entityData(MinecraftServer server, ServerPlayer p) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        var values = p.getEntityData().getNonDefaultValues();
        ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buf, new ClientboundSetEntityDataPacket(p.getId(), values == null ? List.of() : values));
        buf.readVarInt();
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }
}
