package net.fissionmc.backend.mixin.commands;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/commands/Commands.java.patch (RestrictedMarker lives in net.fissionmc.craft.compat) */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Shadow public abstract void performCommand(final ParseResults<CommandSourceStack> command, final String commandString);

    private static final java.util.concurrent.ExecutorService COMMAND_SENDING_POOL = new java.util.concurrent.ThreadPoolExecutor(
        2, 2, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(),
        new com.google.common.util.concurrent.ThreadFactoryBuilder()
            .setNameFormat("Paper Async Command Builder Thread Pool - %1$d")
            .setUncaughtExceptionHandler(new net.minecraft.DefaultUncaughtExceptionHandlerWithName(net.minecraft.server.MinecraftServer.LOGGER))
            .build(),
        new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
    );
    // TODO(behaviour): sendCommands async (sendAsync/runSync), modern constructor, alias flattening, RestrictedMarker handling

    private boolean paper$throwCommandError;

    public void performCommand(final ParseResults<CommandSourceStack> command, final String commandString, final boolean throwCommandError) {
        org.spigotmc.AsyncCatcher.catchOp("Cannot perform command async");
        // Paper end
        // TODO(behaviour): catch hunk rethrows when paper$throwCommandError, always logs, debugging stack traces
        final boolean previous = this.paper$throwCommandError;
        this.paper$throwCommandError = throwCommandError;
        try {
            this.performCommand(command, commandString); // FissionMC: Paper inlines the vanilla body here
        } finally {
            this.paper$throwCommandError = previous;
        }
    }
}
