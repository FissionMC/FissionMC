package net.fissionmc.backend.mixin.network.protocol.game;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.Optionull;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket.java.patch
 * Paper adds constructors (actions, entries) / (actions, entry) and Entry(player, listed) / Entry(id, listed); Mixin cannot add
 * constructors, so {@link #fission$of} and {@link #fission$entry} stand in for them (private helpers, not surface).
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public abstract class ClientboundPlayerInfoUpdatePacketMixin {
    @Shadow @Final @Mutable private EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions;
    @Shadow @Final @Mutable private List<ClientboundPlayerInfoUpdatePacket.Entry> entries;

    // FissionMC: stand-in for Paper's ClientboundPlayerInfoUpdatePacket(EnumSet<Action>, List<Entry>)
    private static ClientboundPlayerInfoUpdatePacket fission$of(EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions, List<ClientboundPlayerInfoUpdatePacket.Entry> entries) {
        ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(actions, List.of());
        ClientboundPlayerInfoUpdatePacketMixin self = (ClientboundPlayerInfoUpdatePacketMixin) (Object) packet;
        self.actions = actions;
        self.entries = entries;
        return packet;
    }

    // FissionMC: stand-in for Paper's Entry(ServerPlayer player, boolean listed) (copy of vanilla Entry(ServerPlayer) with `listed`)
    private static ClientboundPlayerInfoUpdatePacket.Entry fission$entry(ServerPlayer player, boolean listed) {
        return new ClientboundPlayerInfoUpdatePacket.Entry(
            player.getUUID(),
            player.getGameProfile(),
            listed, // Paper - Add Listing API for Player
            player.connection.latency(),
            player.gameMode(),
            player.getTabListDisplayName(),
            player.isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart.HAT),
            player.getTabListOrder(),
            Optionull.map(player.getChatSession(), RemoteChatSession::asData)
        );
    }

    // Paper start - Add Listing API for Player
    // Surface: public static. Mixin requires private statics; the loader widens them.
    private static ClientboundPlayerInfoUpdatePacket createPlayerInitializing(Collection<ServerPlayer> players, ServerPlayer forPlayer) {
        final EnumSet<ClientboundPlayerInfoUpdatePacket.Action> enumSet = EnumSet.of(
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
            ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER
        );
        final List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new java.util.ArrayList<>(players.size());
        final org.bukkit.craftbukkit.entity.CraftPlayer bukkitEntity = forPlayer.getBukkitEntity();
        for (final ServerPlayer player : players) {
            entries.add(fission$entry(player, bukkitEntity.isListed(player.getBukkitEntity())));
        }
        return fission$of(enumSet, entries);
    }

    private static ClientboundPlayerInfoUpdatePacket createSinglePlayerInitializing(ServerPlayer player, boolean listed) {
        final EnumSet<ClientboundPlayerInfoUpdatePacket.Action> enumSet = EnumSet.of(
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
            ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER
        );
        final List<ClientboundPlayerInfoUpdatePacket.Entry> entries = List.of(fission$entry(player, listed));
        return fission$of(enumSet, entries);
    }

    private static ClientboundPlayerInfoUpdatePacket updateListed(UUID playerInfoId, boolean listed) {
        EnumSet<ClientboundPlayerInfoUpdatePacket.Action> enumSet = EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED);
        // Paper: new Entry(playerInfoId, listed)
        return fission$of(enumSet, List.of(new ClientboundPlayerInfoUpdatePacket.Entry(playerInfoId, null, listed, 0, GameType.DEFAULT_MODE, null, true, 0, null)));
    }
    // Paper end - Add Listing API for Player
    // TODO(behaviour): INITIALIZE_CHAT writer drops expired profile public keys
}
