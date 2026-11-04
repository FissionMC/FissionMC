package net.fissionmc.backend.mixin.network.chat;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/network/chat/OutgoingChatMessage.java.patch */
@Mixin(OutgoingChatMessage.Disguised.class)
public abstract class OutgoingChatMessage_DisguisedMixin {
    @Shadow @Final private Component content;

    // TODO(behaviour): vanilla sendToPlayer(3) should delegate here with unsigned = null
    public void sendToPlayer(final ServerPlayer player, final boolean filtered, final ChatType.Bound chatType, final @org.jspecify.annotations.Nullable Component unsigned) {
        player.connection.sendDisguisedChatMessage(unsigned != null ? unsigned : this.content, chatType);
        // Paper end
    }
}
