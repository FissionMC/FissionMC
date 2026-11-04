package net.fissionmc.craft.compat;

import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Paper's {@code net.minecraft.server.players.PlayerList.LoginResult} (sources/net/minecraft/server/players/PlayerList.java.patch).
 * FissionMC: Mixin cannot add nested classes to vanilla classes, so the record lives here.
 */
// Paper start - PlayerLoginEvent
public record LoginResult(@Nullable Component message, org.bukkit.event.player.PlayerLoginEvent.Result result) {
    public static LoginResult ALLOW = new LoginResult(null, org.bukkit.event.player.PlayerLoginEvent.Result.ALLOWED);

    public boolean isAllowed() {
        return this == ALLOW;
    }
}
// Paper end - PlayerLoginEvent
