package net.fissionmc.backend.mixin.network.chat;

import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/network/chat/PlayerChatMessage.java.patch
 * Paper's inner class PlayerChatMessage.AdventureView lives in net.fissionmc.craft.compat.AdventureView.
 * TODO(behaviour): withUnsignedContent handles AdventureComponent
 */
@Mixin(PlayerChatMessage.class)
public abstract class PlayerChatMessageMixin {
    public net.fissionmc.craft.compat.AdventureView adventureView() {
        return new net.fissionmc.craft.compat.AdventureView((PlayerChatMessage) (Object) this);
    }
}
