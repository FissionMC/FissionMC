package net.fissionmc.backend.mixin.server.network;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommandSuggestionsProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/server/network/ServerCommandSuggestionsProvider.java.patch */
@Mixin(ServerCommandSuggestionsProvider.class)
public abstract class ServerCommandSuggestionsProviderMixin {
    @Shadow @Final private ServerPlayer player;

    // Paper - Handle non-recoverable exceptions + Option to disable sending of namespaced commands
    @WrapOperation(method = "tryProcessRequest", at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/CommandDispatcher;getCompletionSuggestions(Lcom/mojang/brigadier/ParseResults;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Suggestions> paper$suggestions(final CommandDispatcher<CommandSourceStack> dispatcher, final ParseResults<CommandSourceStack> parse, final Operation<CompletableFuture<Suggestions>> original) {
        // Paper start - Handle non-recoverable exceptions
        if (!parse.getExceptions().isEmpty()
            && parse.getExceptions().values().stream().anyMatch(e -> e instanceof io.papermc.paper.brigadier.TagParseCommandSyntaxException)) {
            this.player.connection.disconnect(Component.translatable("disconnect.spam"), org.bukkit.event.player.PlayerKickEvent.Cause.SPAM);
            return new CompletableFuture<>(); // never completes; the player is gone
        }
        // Paper end - Handle non-recoverable exceptions
        return original.call(dispatcher, parse).thenApply(suggestions -> {
            // Paper start - Option to disable sending of namespaced commands
            if (!org.spigotmc.SpigotConfig.sendNamespaced && suggestions.getRange().getStart() <= 1) {
                suggestions.getList().removeIf(suggestion -> suggestion.getText().contains(":"));
            }
            // Paper end - Option to disable sending of namespaced commands
            return suggestions;
        });
    }
}
