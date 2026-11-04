package net.fissionmc.backend.mixin.server;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/server/ServerScoreboard.java.patch */
@Mixin(ServerScoreboard.class)
public abstract class ServerScoreboardMixin extends Scoreboard {
    @Shadow @Final private MinecraftServer server;
    @Shadow protected abstract void setDirty();

    // Paper start - Multiple Entries with Scoreboards
    public boolean addPlayersToTeam(java.util.Collection<String> players, PlayerTeam team) {
        boolean anyAdded = false;
        for (String playerName : players) {
            if (super.addPlayerToTeam(playerName, team)) {
                anyAdded = true;
            }
        }

        if (anyAdded) {
            this.broadcastAll(ClientboundSetPlayerTeamPacket.createMultiplePlayerPacket(team, players, ClientboundSetPlayerTeamPacket.Action.ADD));
            this.setDirty();
            return true;
        } else {
            return false;
        }
    }

    public void removePlayersFromTeam(java.util.Collection<String> players, PlayerTeam team) {
        for (String playerName : players) {
            super.removePlayerFromTeam(playerName, team);
        }

        this.broadcastAll(ClientboundSetPlayerTeamPacket.createMultiplePlayerPacket(team, players, ClientboundSetPlayerTeamPacket.Action.REMOVE));
        this.setDirty();
    }
    // Paper end - Multiple Entries with Scoreboards

    // CraftBukkit start - Send to players
    private void broadcastAll(Packet<?> packet) {
        for (ServerPlayer serverPlayer : this.server.getPlayerList().getPlayers()) {
            if (serverPlayer.getBukkitEntity().getScoreboard().getHandle() == (Object) this) {
                serverPlayer.connection.send(packet);
            }
        }
    }
    // CraftBukkit end
}
