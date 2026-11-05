package net.fissionmc.backend.mixin.commands;

import com.mojang.brigadier.ParseResults;
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

    // Paper start
    public void performCommand(final ParseResults<CommandSourceStack> command, final String commandString, final boolean throwCommandError) {
        // TODO(behaviour): Paper moves the vanilla performCommand body here (AsyncCatcher, rethrow when
        // throwCommandError, UnknownCommandEvent, console logging); the 2-arg overload delegates with false.
        this.performCommand(command, commandString);
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

    // TODO(behaviour): constructor tail (vanilla permission fixes via ArgumentBuilder.defaultRequirement(),
    // "minecraft:" namespaced copies), isRestricted RestrictedMarker check, sendCommands async building
    // (sendAsync/runSync on COMMAND_SENDING_POOL, clientNode, tab-complete=-1 empty packet), fillUsableCommands changes.
}
