package net.fissionmc.backend.mixin.server.level;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/server/level/ServerPlayerGameMode.java.patch */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Shadow private GameType gameModeForPlayer;
    @Shadow @Final protected ServerPlayer player;

    @Shadow public abstract boolean changeGameModeForPlayer(GameType gameModeForPlayer);

    // TODO(behaviour): Paper makes changeGameModeForPlayer(GameType) delegate here with Cause.UNKNOWN; this overload fires the
    // event and then reuses the (event-free) vanilla body. Once the 1-arg method fires the event itself, inline the body here instead.
    public org.bukkit.event.player.@Nullable PlayerGameModeChangeEvent changeGameModeForPlayer(final GameType gameModeForPlayer, final org.bukkit.event.player.PlayerGameModeChangeEvent.Cause playerGameModeChangeCause, final net.kyori.adventure.text.@Nullable Component cancelMessage) {
        // Paper end - Expand PlayerGameModeChangeEvent
        if (gameModeForPlayer == this.gameModeForPlayer) {
            return null; // Paper - Expand PlayerGameModeChangeEvent
        }
        // CraftBukkit start
        org.bukkit.event.player.PlayerGameModeChangeEvent event = new org.bukkit.event.player.PlayerGameModeChangeEvent(
            this.player.getBukkitEntity(),
            org.bukkit.GameMode.getByValue(gameModeForPlayer.getId()),
            playerGameModeChangeCause,
            cancelMessage
        );
        if (!event.callEvent()) {
            return event; // Paper - Expand PlayerGameModeChangeEvent
        }
        // CraftBukkit end
        this.changeGameModeForPlayer(gameModeForPlayer);
        return event; // Paper - Expand PlayerGameModeChangeEvent
    }
}
