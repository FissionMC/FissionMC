package net.fissionmc.backend.mixin.network.protocol.game;

import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/network/protocol/game/ClientboundSystemChatPacket.java.patch
 * Mixin cannot add constructors: the Spigot/Paper constructors are static factories `create(...)`; ported call sites use them.
 */
@Mixin(ClientboundSystemChatPacket.class)
public abstract class ClientboundSystemChatPacketMixin {
    // Surface: public static. Mixin requires private statics; the loader widens them.
    // Spigot start
    private static ClientboundSystemChatPacket create(net.md_5.bungee.api.chat.BaseComponent[] content, boolean overlay) {
        return new ClientboundSystemChatPacket(org.bukkit.craftbukkit.util.CraftChatMessage.bungeeToVanilla(content), overlay);
    }
    // Spigot end
    // Paper start
    private static ClientboundSystemChatPacket create(net.kyori.adventure.text.Component content, boolean overlay) {
        return new ClientboundSystemChatPacket(io.papermc.paper.adventure.PaperAdventure.asVanilla(content), overlay);
    }
    // Paper end
}
