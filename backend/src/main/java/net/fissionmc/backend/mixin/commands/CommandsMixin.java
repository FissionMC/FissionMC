package net.fissionmc.backend.mixin.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandBuildContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Paper: sources/net/minecraft/commands/Commands.java.patch.
 * The nested {@code Commands.RestrictedMarker} lives in craft as {@code net.fissionmc.craft.compat.RestrictedMarker}.
 * Paper's {@code Commands(CommandSelection, CommandBuildContext, boolean modern)} constructor cannot be added by
 * Mixin; nothing in craft calls it.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Shadow public abstract void performCommand(ParseResults<CommandSourceStack> command, String commandString);
    @Shadow private static void validateParseResults(ParseResults<CommandSourceStack> command) throws CommandSyntaxException {}

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

    @Shadow private static @Nullable ContextChain<CommandSourceStack> finishParsing(ParseResults<CommandSourceStack> command, String commandString, CommandSourceStack sender) { throw new AssertionError(); }
    @Shadow public static void executeCommandInContext(CommandSourceStack context, java.util.function.Consumer<net.minecraft.commands.execution.ExecutionContext<CommandSourceStack>> config) {}

    // Paper: vanilla performCommand(parse, string) delegates to the 3-arg overload (rethrow when requested, always log execution exceptions)
    @Inject(method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void fission$performCommand2(final ParseResults<CommandSourceStack> command, final String commandString, final CallbackInfo ci) {
        ci.cancel();
        this.performCommand(command, commandString, false);
    }

    // Paper start
    public void performCommand(final ParseResults<CommandSourceStack> command, final String commandString, final boolean throwCommandError) {
        org.spigotmc.AsyncCatcher.catchOp("Cannot perform command async");
        final CommandSourceStack sender = command.getContext().getSource();
        net.minecraft.util.profiling.Profiler.get().push(() -> "/" + commandString);
        final ContextChain<CommandSourceStack> commandChain = finishParsing(command, commandString, sender);
        try {
            if (commandChain != null) {
                executeCommandInContext(
                    sender,
                    executionContext -> net.minecraft.commands.execution.ExecutionContext.queueInitialCommandExecution(
                        executionContext, commandString, commandChain, sender, net.minecraft.commands.CommandResultCallback.EMPTY
                    )
                );
            }
        } catch (final Throwable e) { // always gracefully handle it, no matter how bad:tm:
            if (throwCommandError) throw sneaky(e); // rethrow directly if requested
            final MutableComponent hover = Component.literal(e.getMessage() == null ? e.getClass().getName() : e.getMessage());
            MinecraftServer.LOGGER.error("Command exception: /{}", commandString, e); // Paper - always show execution exception in console log
            if (sender.getServer().isDebugging() || MinecraftServer.LOGGER.isDebugEnabled()) { // Paper - Debugging
                final StackTraceElement[] stackTrace = e.getStackTrace();
                for (int i = 0; i < Math.min(stackTrace.length, 3); i++) {
                    hover.append("\n\n").append(stackTrace[i].getMethodName()).append("\n ").append(stackTrace[i].getFileName()).append(":").append(String.valueOf(stackTrace[i].getLineNumber()));
                }
            }
            sender.sendFailure(Component.translatable("command.failed").withStyle(style -> style.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(hover))));
            if (net.minecraft.SharedConstants.DEBUG_VERBOSE_COMMAND_ERRORS || net.minecraft.SharedConstants.IS_RUNNING_IN_IDE) {
                sender.sendFailure(Component.literal(net.minecraft.util.Util.describeError(e)));
                MinecraftServer.LOGGER.error("'/{}' threw an exception", commandString, e);
            }
        } finally {
            net.minecraft.util.profiling.Profiler.get().pop();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(final Throwable t) throws T {
        throw (T) t;
    }
    // Paper end

    // replaced body: Paper - Add UnknownCommandEvent
    @Inject(method = "finishParsing", at = @At("HEAD"), cancellable = true)
    private static void fission$finishParsing(ParseResults<CommandSourceStack> command, String commandString, CommandSourceStack sender, CallbackInfoReturnable<@Nullable ContextChain<CommandSourceStack>> cir) {
        try {
            validateParseResults(command);
            cir.setReturnValue(ContextChain.tryFlatten(command.getContext().build(commandString))
                .orElseThrow(() -> CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(command.getReader())));
        } catch (CommandSyntaxException e) {
            // Paper start - Add UnknownCommandEvent
            final net.kyori.adventure.text.TextComponent.Builder builder = net.kyori.adventure.text.Component.text();
            // sender.sendFailure(ComponentUtils.fromMessage(var7.getRawMessage()));
            builder.color(net.kyori.adventure.text.format.NamedTextColor.RED).append(io.papermc.paper.command.brigadier.MessageComponentSerializer.message().deserialize(e.getRawMessage()));
            // Paper end - Add UnknownCommandEvent
            if (e.getInput() != null && e.getCursor() >= 0) {
                int cursor = Math.min(e.getInput().length(), e.getCursor());
                MutableComponent context = Component.empty()
                    .withStyle(ChatFormatting.GRAY)
                    .withStyle(s -> s.withClickEvent(new ClickEvent.SuggestCommand("/" + net.minecraft.util.StringUtil.filterText(commandString)))); // Paper - Filter out disallowed control chars
                if (cursor > 10) {
                    context.append(CommonComponents.ELLIPSIS);
                }

                context.append(e.getInput().substring(Math.max(0, cursor - 10), cursor));
                if (cursor < e.getInput().length()) {
                    Component problem = Component.literal(e.getInput().substring(cursor)).withStyle(ChatFormatting.RED, ChatFormatting.UNDERLINE);
                    context.append(problem);
                }

                context.append(Component.translatable("command.context.here").withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
                // Paper start - Add UnknownCommandEvent
                // sender.sendFailure(context);
                builder
                    .append(net.kyori.adventure.text.Component.newline())
                    .append(io.papermc.paper.adventure.PaperAdventure.asAdventure(context));
            }
            org.bukkit.event.command.UnknownCommandEvent event = new org.bukkit.event.command.UnknownCommandEvent(sender, commandString, org.spigotmc.SpigotConfig.unknownCommandMessage.isEmpty() ? null : builder.build());
            org.bukkit.Bukkit.getServer().getPluginManager().callEvent(event);
            if (event.message() != null) {
                sender.sendFailure(io.papermc.paper.adventure.PaperAdventure.asVanilla(event.message()), false);
                // Paper end - Add UnknownCommandEvent
            }

            cir.setReturnValue(null);
        }
    }

    // CraftBukkit start - PlayerCommandSendEvent (FissionMC: built synchronously; Paper builds the map on COMMAND_SENDING_POOL)
    @WrapOperation(method = "sendCommands", at = @At(value = "NEW", target = "(Lcom/mojang/brigadier/tree/RootCommandNode;Lnet/minecraft/network/protocol/game/ClientboundCommandsPacket$NodeInspector;)Lnet/minecraft/network/protocol/game/ClientboundCommandsPacket;"))
    private ClientboundCommandsPacket fission$sendCommandsEvent(RootCommandNode<?> root, ClientboundCommandsPacket.NodeInspector<?> inspector, Operation<ClientboundCommandsPacket> original, @Local(argsOnly = true) ServerPlayer player) {
        java.util.Collection<String> bukkit = new java.util.LinkedHashSet<>();
        for (com.mojang.brigadier.tree.CommandNode<?> node : root.getChildren()) {
            bukkit.add(node.getName());
        }
        @SuppressWarnings("unchecked")
        RootCommandNode<CommandSourceStack> typedRoot = (RootCommandNode<CommandSourceStack>) root;
        new com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent<>(player.getBukkitEntity(), typedRoot, false).callEvent(); // Paper - Brigadier API
        new com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent<>(player.getBukkitEntity(), typedRoot, true).callEvent(); // Paper - Brigadier API
        org.bukkit.event.player.PlayerCommandSendEvent event = new org.bukkit.event.player.PlayerCommandSendEvent(player.getBukkitEntity(), new java.util.LinkedHashSet<>(bukkit));
        event.getPlayer().getServer().getPluginManager().callEvent(event);

        // Remove labels that were removed during the event
        for (String orig : bukkit) {
            if (!event.getCommands().contains(orig)) {
                root.removeCommand(orig);
            }
        }
        return original.call(root, inspector);
    }
    // CraftBukkit end

    @Shadow @Final private CommandDispatcher<CommandSourceStack> dispatcher;

    // Paper - Vanilla command permission fixes + Brigadier Command API legacy "minecraft:" namespaced commands (constructor tail; Paper's `modern` overload is not added, see class doc)
    @Inject(method = "<init>(Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;)V", at = @At("TAIL"))
    private void fission$vanillaPermissionFixes(final Commands.CommandSelection selection, final CommandBuildContext context, final CallbackInfo ci) {
        for (final CommandNode<CommandSourceStack> node : this.dispatcher.getRoot().getChildren()) {
            if (node.getRequirement() == com.mojang.brigadier.builder.ArgumentBuilder.<CommandSourceStack>defaultRequirement()) {
                node.requirement = stack -> stack.source == net.minecraft.commands.CommandSource.NULL || stack.getBukkitSender().hasPermission(org.bukkit.craftbukkit.command.VanillaCommandWrapper.getPermission(node));
            } else if (node.getRequirement() instanceof net.minecraft.server.permissions.PermissionProviderCheck<CommandSourceStack> check) {
                net.fissionmc.backend.commands.VanillaCommandPermissions.register(check, node);
            }
        }
        // Create legacy minecraft namespace commands (flattened redirecting node, Paper's non-modern behaviour)
        for (final CommandNode<CommandSourceStack> node : new java.util.ArrayList<>(this.dispatcher.getRoot().getChildren())) {
            CommandNode<CommandSourceStack> flattenedAliasTarget = node;
            while (flattenedAliasTarget.getRedirect() != null) flattenedAliasTarget = flattenedAliasTarget.getRedirect();
            this.dispatcher.register(
                com.mojang.brigadier.builder.LiteralArgumentBuilder.<CommandSourceStack>literal("minecraft:" + node.getName())
                    .executes(flattenedAliasTarget.getCommand())
                    .requires(flattenedAliasTarget.getRequirement())
                    .redirect(flattenedAliasTarget));
        }
    }

    // Paper - Send empty commands if tab completion is disabled
    @Inject(method = "sendCommands", at = @At("HEAD"), cancellable = true)
    private void fission$noCommandsWithoutTabComplete(final ServerPlayer player, final CallbackInfo ci) {
        if (org.spigotmc.SpigotConfig.tabComplete < 0) {
            player.connection.send(new ClientboundCommandsPacket(new RootCommandNode<>(), COMMAND_NODE_INSPECTOR));
            ci.cancel();
        }
    }

    @Shadow @Final private static ClientboundCommandsPacket.NodeInspector<CommandSourceStack> COMMAND_NODE_INSPECTOR;

    // Paper: fillUsableCommands. Replaced body: Brigadier API client nodes (custom/wrapped arguments), Spigot's send-namespaced,
    // and the "dead redirect" repair (a plugin removed the node a minecraft:x copy redirects to, e.g. Essentials replacing /msg).
    @Inject(method = "fillUsableCommands", at = @At("HEAD"), cancellable = true)
    private static <S> void fission$fillUsableCommandsHook(
        final CommandNode<S> source, final CommandNode<S> target, final S commandFilter, final java.util.Map<CommandNode<S>, CommandNode<S>> converted, final CallbackInfo ci
    ) {
        ci.cancel();
        fission$fillUsableCommands(source, target, commandFilter, converted);
    }

    private static <S> void fission$fillUsableCommands(
        final CommandNode<S> source, final CommandNode<S> target, final S commandFilter, final java.util.Map<CommandNode<S>, CommandNode<S>> converted
    ) {
        for (CommandNode<S> child : source.getChildren()) {
            // Paper start - Brigadier API
            if (child.clientNode != null) {
                child = child.clientNode;
            }
            // Paper end - Brigadier API
            if (!org.spigotmc.SpigotConfig.sendNamespaced && child.getName().contains(":")) continue; // Spigot
            if (child.canUse(commandFilter)) {
                com.mojang.brigadier.builder.ArgumentBuilder<S, ?> builder = child.createBuilder();
                // Paper start
                // Is there an invalid command redirect?
                if (builder.getRedirect() != null && converted.get(builder.getRedirect()) == null) {
                    // Create the argument builder with the same values as the specified node, but with a different literal and populated children
                    final CommandNode<S> redirect = builder.getRedirect();
                    // Diff copied from LiteralCommand#createBuilder
                    final com.mojang.brigadier.builder.LiteralArgumentBuilder<S> redirectBuilder = com.mojang.brigadier.builder.LiteralArgumentBuilder.literal(child.getName());
                    redirectBuilder.requires(redirect.getRequirement());
                    // builder.forward(redirect.getRedirect(), redirect.getRedirectModifier(), redirect.isFork()); We don't want to migrate the forward, since it's invalid.
                    if (redirect.getCommand() != null) {
                        redirectBuilder.executes(redirect.getCommand());
                    }
                    // Diff copied from LiteralCommand#createBuilder
                    for (final CommandNode<S> redirectChild : redirect.getChildren()) {
                        redirectBuilder.then(redirectChild);
                    }

                    builder = redirectBuilder;
                }
                // Paper end
                if (builder.getRedirect() != null) {
                    builder.redirect(converted.get(builder.getRedirect()));
                }

                final CommandNode<S> node = builder.build();
                converted.put(child, node);
                target.addChild(node);
                if (!child.getChildren().isEmpty()) {
                    fission$fillUsableCommands(child, node, commandFilter, converted);
                }
            }
        }
    }

    // TODO(behaviour): sendCommands async building (sendAsync/runSync on COMMAND_SENDING_POOL): built synchronously here.
}
