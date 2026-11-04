package net.fissionmc.backend.mixin.network.chat;

import net.minecraft.network.chat.ChatDecorator;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/network/chat/ChatDecorator.java.patch
 * FissionMC: Paper changes decorate(ServerPlayer, Component) to return CompletableFuture; a vanilla method's return type
 * cannot change, so the 2-arg method stays vanilla (ImprovedChatDecorator joins the future).
 * TODO(behaviour): PLAIN and the async decoration pipeline in ServerGamePacketListenerImpl / MessageArgument.
 */
@Mixin(ChatDecorator.class)
public interface ChatDecoratorMixin {
    // Paper start - adventure; support async chat decoration events
    default java.util.concurrent.CompletableFuture<Component> decorate(@Nullable ServerPlayer sender, net.minecraft.commands.@Nullable CommandSourceStack commandSourceStack, Component plain) {
        throw new UnsupportedOperationException("Must override this implementation");
    }
    // Paper end - adventure; support async chat decoration events
}
