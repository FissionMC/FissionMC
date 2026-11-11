package net.fissionmc.backend.mixin.server.commands;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.GameModeCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/server/commands/GameModeCommand.java.patch (replaced body of the private setGameMode: PlayerGameModeChangeEvent with Cause.COMMAND, cancel message; the public 4-arg overload is not added) */
@Mixin(GameModeCommand.class)
public abstract class GameModeCommandMixin {
    @Shadow private static void logGamemodeChange(final CommandSourceStack source, final ServerPlayer player, final GameType type) { throw new AssertionError(); }

    @Inject(method = "setGameMode(Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/GameType;)Z", at = @At("HEAD"), cancellable = true)
    private static void paper$setGameMode(final CommandSourceStack source, final ServerPlayer player, final GameType type, final CallbackInfoReturnable<Boolean> cir) {
        // Paper start - Expand PlayerGameModeChangeEvent
        org.bukkit.event.player.PlayerGameModeChangeEvent event = player.setGameMode(type, org.bukkit.event.player.PlayerGameModeChangeEvent.Cause.COMMAND, null);
        if (event != null && !event.isCancelled()) {
            logGamemodeChange(source, player, type);
            cir.setReturnValue(true);
        } else if (event != null && event.cancelMessage() != null) {
            source.sendSuccess(() -> io.papermc.paper.adventure.PaperAdventure.asVanilla(event.cancelMessage()), true);
            cir.setReturnValue(false);
        } else {
            cir.setReturnValue(false);
        }
        // Paper end - Expand PlayerGameModeChangeEvent
    }
}
