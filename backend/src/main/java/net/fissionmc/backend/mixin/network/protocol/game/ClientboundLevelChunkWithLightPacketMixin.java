package net.fissionmc.backend.mixin.network.protocol.game;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket.java.patch */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class ClientboundLevelChunkWithLightPacketMixin {
    // Paper start - Anti-Xray
    public void setReady(final boolean ready) {
        // Empty hook, updated by feature patch
        // TODO(behaviour): features/0024-Anti-Xray.patch
    }
    // Paper end - Anti-Xray
}
