package net.fissionmc.backend.mixin.network.protocol.game;

import java.util.Collection;
import java.util.Optional;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/network/protocol/game/ClientboundSetPlayerTeamPacket.java.patch
 * TODO(behaviour): Parameters collisionRule/nameTagVisibility overrides.
 */
@Mixin(ClientboundSetPlayerTeamPacket.class)
public abstract class ClientboundSetPlayerTeamPacketMixin {
    @Shadow @Final private static int METHOD_JOIN;
    @Shadow @Final private static int METHOD_LEAVE;

    // Paper start - Multiple Entries with Scoreboards
    // Surface: public static (see surface/members/player.txt); the loader widens it.
    private static ClientboundSetPlayerTeamPacket createMultiplePlayerPacket(PlayerTeam team, Collection<String> players, ClientboundSetPlayerTeamPacket.Action action) {
        return new ClientboundSetPlayerTeamPacket(team.getName(), action == ClientboundSetPlayerTeamPacket.Action.ADD ? METHOD_JOIN : METHOD_LEAVE, Optional.empty(), players);
    }
    // Paper end - Multiple Entries with Scoreboards
}
