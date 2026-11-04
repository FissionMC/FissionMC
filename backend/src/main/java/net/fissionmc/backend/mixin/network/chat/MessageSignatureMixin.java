package net.fissionmc.backend.mixin.network.chat;

import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/network/chat/MessageSignature.java.patch */
@Mixin(MessageSignature.class)
public abstract class MessageSignatureMixin {
    @Shadow @Final private byte[] bytes;

    public net.kyori.adventure.chat.SignedMessage.Signature adventure() { return net.kyori.adventure.chat.SignedMessage.signature(this.bytes); } // Paper - adventure; support signed messages
}
