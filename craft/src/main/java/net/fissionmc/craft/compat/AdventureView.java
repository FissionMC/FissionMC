package net.fissionmc.craft.compat;

import java.time.Instant;
import net.minecraft.network.chat.PlayerChatMessage;
import org.jspecify.annotations.Nullable;

/**
 * Paper's {@code net.minecraft.network.chat.PlayerChatMessage.AdventureView} (sources/net/minecraft/network/chat/PlayerChatMessage.java.patch).
 * FissionMC: Mixin cannot add inner classes to vanilla classes; the outer instance is passed explicitly.
 */
// Paper start - adventure; support signed messages
public final class AdventureView implements net.kyori.adventure.chat.SignedMessage {
    private final PlayerChatMessage message;

    public AdventureView(final PlayerChatMessage message) {
        this.message = message;
    }
    @Override
    public Instant timestamp() {
        return this.message.timeStamp();
    }
    @Override
    public long salt() {
        return this.message.salt();
    }
    @Override
    public @Nullable Signature signature() {
        return this.message.signature() == null ? null : this.message.signature().adventure();
    }
    @Override
    public net.kyori.adventure.text.@Nullable Component unsignedContent() {
        return this.message.unsignedContent() == null ? null : io.papermc.paper.adventure.PaperAdventure.asAdventure(this.message.unsignedContent());
    }
    @Override
    public String message() {
        return this.message.signedContent();
    }
    @Override
    public net.kyori.adventure.identity.Identity identity() {
        return net.kyori.adventure.identity.Identity.identity(this.message.sender());
    }
    public PlayerChatMessage playerChatMessage() {
        return this.message;
    }
}
// Paper end - adventure; support signed messages
