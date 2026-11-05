package net.fissionmc.backend.mixin.world.scores;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.Collection;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/scores/Scoreboard.java.patch */
@Mixin(Scoreboard.class)
public abstract class ScoreboardMixin {

    @Inject(method = "removePlayerTeam", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/scores/Scoreboard;onTeamRemoved(Lnet/minecraft/world/scores/PlayerTeam;)V", shift = At.Shift.AFTER))
    private void paper$clearTeamEntries(PlayerTeam team, CallbackInfo ci) {
        team.getPlayers().clear(); // Paper - Clear scoreboard team entries on unregister
    }

    // Paper - Don't save empty scoreboard teams to scoreboard.dat
    @ModifyExpressionValue(method = "packPlayerTeams", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/scores/Scoreboard;getPlayerTeams()Ljava/util/Collection;"))
    private Collection<PlayerTeam> paper$skipEmptyTeams(Collection<PlayerTeam> teams) {
        if (io.papermc.paper.configuration.GlobalConfiguration.get().scoreboards.saveEmptyScoreboardTeams) return teams;
        return teams.stream().filter(p -> !p.getPlayers().isEmpty()).toList(); // Paper - Don't save empty scoreboard teams to scoreboard.dat
    }
}
