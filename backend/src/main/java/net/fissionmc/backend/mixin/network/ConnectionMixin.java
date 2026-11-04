package net.fissionmc.backend.mixin.network;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/network/Connection.java.patch */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Shadow public Channel channel;
    @Shadow private volatile @Nullable PacketListener packetListener;

    // Surface: public fields. Mixin requires private fields; the loader widens them.
    // Spigot start
    private java.util.UUID spoofedUUID;
    private com.mojang.authlib.properties.Property[] spoofedProfile;
    private boolean preparing = true; // TODO(behaviour): set false in channelActive/disconnect
    // Spigot end
    private String hostname = ""; // CraftBukkit - add field
    private boolean handleConnectionDisconnectOnNextTick = false; // Paper - Force kill connection ticking // TODO(behaviour): consumed in tick()
    // Paper start - NetworkClient implementation
    private int protocolVersion;
    private java.net.InetSocketAddress virtualHost;
    // Paper end
    // Paper start - add utility methods
    public final net.minecraft.server.level.ServerPlayer getPlayer() {
        if (this.packetListener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl impl) {
            return impl.player;
        } else if (this.packetListener instanceof net.minecraft.server.network.ServerCommonPacketListenerImpl impl) {
            return null;
        }
        return null;
    }
    // Paper end - add utility methods
    // TODO(behaviour): packet limiter fields/logic (features/0005, Connection.java.patch)
    private java.net.@Nullable SocketAddress haProxyAddress; // Paper - Add API to get player's proxy address
    private java.util.@Nullable Optional<net.minecraft.network.chat.Component> legacySavedLoginEventResultOverride; // Paper - playerloginevent
    private boolean handledLegacyLoginEvent; // Paper - playerloginevent
    private net.minecraft.server.level.@Nullable ServerPlayer savedPlayerForLegacyEvents; // Paper - playerloginevent & PlayerSpawnLocationEvent
    private org.bukkit.event.player.PlayerResourcePackStatusEvent.@Nullable Status resourcePackStatus; // Paper

    // Paper start - add proper async disconnect
    public void enableAutoRead() {
        if (this.channel != null) {
            this.channel.config().setAutoRead(true);
        }
    }
    // Paper end - add proper async disconnect
}
