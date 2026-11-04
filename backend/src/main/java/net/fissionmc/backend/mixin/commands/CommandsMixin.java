package net.fissionmc.backend.mixin.commands;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/commands/Commands.java.patch.
 * The nested {@code Commands.RestrictedMarker} lives in craft as {@code net.fissionmc.craft.compat.RestrictedMarker}.
 * Paper's {@code Commands(CommandSelection, CommandBuildContext, boolean modern)} constructor cannot be added by
 * Mixin; nothing in craft calls it.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Shadow public abstract void performCommand(ParseResults<CommandSourceStack> command, String commandString);

    // Paper start - Perf: Async command map building
    // Fixed pool, but with discard policy
    // Surface: public static final. Mixin requires non-public fields; the loader widens it.
    private static final java.util.concurrent.ExecutorService COMMAND_SENDING_POOL = new java.util.concurrent.ThreadPoolExecutor(
        2, 2, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(),
        new com.google.common.util.concurrent.ThreadFactoryBuilder()
            .setNameFormat("Paper Async Command Builder Thread Pool - %1$d")
            .setUncaughtExceptionHandler(new net.minecraft.DefaultUncaughtExceptionHandlerWithName(MinecraftServer.LOGGER))
            .build(),
        new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
    );
    // Paper end - Perf: Async command map building

    // Paper start
    public void performCommand(final ParseResults<CommandSourceStack> command, final String commandString, final boolean throwCommandError) {
        // TODO(behaviour): Paper moves the vanilla performCommand body here (AsyncCatcher, rethrow when
        // throwCommandError, UnknownCommandEvent, console logging); the 2-arg overload delegates with false.
        this.performCommand(command, commandString);
    }
    // Paper end

    // TODO(behaviour): constructor tail (vanilla permission fixes via ArgumentBuilder.defaultRequirement(),
    // "minecraft:" namespaced copies), isRestricted RestrictedMarker check, sendCommands async building
    // (sendAsync/runSync on COMMAND_SENDING_POOL, PlayerCommandSendEvent, clientNode), fillUsableCommands changes.
}
