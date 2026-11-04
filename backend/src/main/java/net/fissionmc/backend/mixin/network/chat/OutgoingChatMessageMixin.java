package net.fissionmc.backend.mixin.network.chat;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/network/chat/OutgoingChatMessage.java.patch */
@Mixin(OutgoingChatMessage.class)
public interface OutgoingChatMessageMixin {
    // Paper start
    default void sendToPlayer(final ServerPlayer player, final boolean filtered, final ChatType.Bound chatType, final @org.jspecify.annotations.Nullable Component unsigned) {
        ((OutgoingChatMessage) this).sendToPlayer(player, filtered, chatType);
    }
    // Paper end
}
