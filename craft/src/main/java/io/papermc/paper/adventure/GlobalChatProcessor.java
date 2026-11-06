package io.papermc.paper.adventure;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AbstractChatEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.ChatEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.util.Waitable;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChatEvent;

import static net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection;

/**
 * FissionMC: runs the Paper chat event path (AsyncPlayerChatEvent, PlayerChatEvent, AsyncChatEvent, ChatEvent, in the order of
 * {@link ChatProcessor#process()}) for a GLOBAL chat message with an EMPTY recipient/viewer set, and stops before delivery. The master
 * broadcasts the outcome. New file next to ChatProcessor (the ported class is untouched); the event logic mirrors it.
 *
 * Differences to ChatProcessor, all intended: recipients and viewers are empty (a plugin may add to them; nothing is delivered to them
 * anyway), the renderer/format is not applied (the master owns the format), no delivery.
 */
public final class GlobalChatProcessor {
    /** Outcome: {@code message} is the final message component, {@code modified} whether plugins changed it. */
    public record Result(boolean cancelled, boolean modified, Component message, int legacyListeners, int modernListeners) {}

    private final MinecraftServer server;
    private final ServerPlayer player;
    private final boolean async;

    private GlobalChatProcessor(MinecraftServer server, ServerPlayer player, boolean async) {
        this.server = server;
        this.player = player;
        this.async = async;
    }

    public static Result run(MinecraftServer server, ServerPlayer player, PlayerChatMessage message, boolean async) {
        return new GlobalChatProcessor(server, player, async).process(message);
    }

    @SuppressWarnings("deprecation")
    private Result process(PlayerChatMessage message) {
        final ChatProcessor ref = new ChatProcessor(this.server, this.player, message, this.async);
        final String legacyOriginal = ref.craftbukkit$originalMessage;
        final Component modernOriginal = ref.paper$originalMessage;
        final CraftPlayer bukkit = this.player.getBukkitEntity();

        final boolean listenersOnAsyncEvent = ChatProcessor.canYouHearMe(AsyncPlayerChatEvent.getHandlerList());
        final boolean listenersOnSyncEvent = ChatProcessor.canYouHearMe(PlayerChatEvent.getHandlerList());
        Component modernMessage = modernOriginal;
        boolean cancelled = false;
        Player sender = bukkit;
        if (listenersOnAsyncEvent || listenersOnSyncEvent) {
            final AsyncPlayerChatEvent asyncChatEvent = new AsyncPlayerChatEvent(this.async, bukkit, legacyOriginal, new HashSet<Player>());
            this.post(asyncChatEvent);
            String legacyMessage = asyncChatEvent.getMessage();
            cancelled = asyncChatEvent.isCancelled();
            sender = asyncChatEvent.getPlayer();
            if (listenersOnSyncEvent) {
                final PlayerChatEvent chatEvent = new PlayerChatEvent(bukkit, asyncChatEvent.getMessage(), asyncChatEvent.getFormat(), asyncChatEvent.getRecipients());
                chatEvent.setCancelled(asyncChatEvent.isCancelled());
                this.queueIfAsyncOrRunImmediately(new Waitable<>() {
                    @Override
                    protected Void evaluate() {
                        GlobalChatProcessor.this.post(chatEvent);
                        return null;
                    }
                });
                legacyMessage = chatEvent.getMessage();
                cancelled = chatEvent.isCancelled();
                sender = chatEvent.getPlayer();
            }
            if (!legacyMessage.equals(legacyOriginal)) {
                modernMessage = legacySection().deserialize(legacyMessage);
            }
        }

        final net.fissionmc.craft.compat.AdventureView signedMessage = message.adventureView();
        final Set<Audience> viewers = new HashSet<>();
        final ChatRenderer renderer = ChatRenderer.defaultRenderer();
        final AsyncChatEvent ae = new AsyncChatEvent(this.async, sender, viewers, renderer, modernMessage, modernOriginal, signedMessage);
        ae.setCancelled(cancelled);
        this.post(ae);
        AbstractChatEvent last = ae;
        if (ChatProcessor.canYouHearMe(ChatEvent.getHandlerList())) {
            final ChatEvent[] holder = new ChatEvent[1];
            final Player chatSender = sender;
            this.queueIfAsyncOrRunImmediately(new Waitable<>() {
                @Override
                protected Void evaluate() {
                    final ChatEvent chatEvent = new ChatEvent(chatSender, ae.viewers(), ae.renderer(), ae.message(), modernOriginal, signedMessage);
                    chatEvent.setCancelled(ae.isCancelled());
                    GlobalChatProcessor.this.post(chatEvent);
                    holder[0] = chatEvent;
                    return null;
                }
            });
            if (holder[0] != null) last = holder[0];
        }
        final Component fin = last.message();
        return new Result(last.isCancelled(), !fin.equals(modernOriginal), fin,
            AsyncPlayerChatEvent.getHandlerList().getRegisteredListeners().length + PlayerChatEvent.getHandlerList().getRegisteredListeners().length,
            AsyncChatEvent.getHandlerList().getRegisteredListeners().length + ChatEvent.getHandlerList().getRegisteredListeners().length);
    }

    private void queueIfAsyncOrRunImmediately(final Waitable<Void> waitable) {
        if (this.async) {
            this.server.processQueue.add(waitable);
        } else {
            waitable.run();
        }
        try {
            waitable.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
            throw new RuntimeException("Exception processing chat", e.getCause());
        }
    }

    private void post(final Event event) {
        this.server.server.getPluginManager().callEvent(event);
    }
}
